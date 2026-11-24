package pt.isep.bmc.issue.application.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import pt.isep.bmc.issue.application.error.IdempotencyKeyReusedException;
import pt.isep.bmc.issue.application.port.out.DuplicateIdempotencyKeyException;
import pt.isep.bmc.issue.application.port.out.IdempotencyRecord;
import pt.isep.bmc.issue.application.port.out.IdempotencyScope;
import pt.isep.bmc.issue.application.port.out.IdempotencyStore;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;

/**
 * Runs an effectful command at most once per idempotency key (technical
 * design 3.3 and 4.1).
 *
 * <p>The command's own transaction must make {@link IdempotencyStore#record}
 * its <b>first write</b>. A concurrent retry with the same key then blocks on
 * that unique row until the first transaction ends; if it committed, the
 * retry fails with {@link DuplicateIdempotencyKeyException}, rolls back
 * everything it did and replays the stored result. A request that is rejected
 * rolls back its key too, so a rejected request leaves no key behind. A
 * different request under the same key is refused with
 * {@link IdempotencyKeyReusedException} and changes nothing.
 */
public final class IdempotentExecutor {

    private final TransactionRunner transactions;
    private final IdempotencyStore idempotency;

    public IdempotentExecutor(TransactionRunner transactions, IdempotencyStore idempotency) {
        this.transactions = transactions;
        this.idempotency = idempotency;
    }

    /**
     * @param requestHash see {@link #fingerprint}
     * @param effect      performs the command; runs in one transaction and records the key first
     * @param replay      rebuilds the original result from the stored record; runs read-only
     */
    public <T> T execute(
            IdempotencyScope scope,
            String requestHash,
            Instant now,
            Supplier<T> effect,
            Function<IdempotencyRecord, T> replay) {
        Optional<IdempotencyRecord> stored = transactions.readOnly(() -> idempotency.find(scope));
        if (stored.isPresent() && !stored.get().isExpiredAt(now)) {
            return transactions.readOnly(() -> replay.apply(sameRequest(stored.get(), requestHash)));
        }
        if (stored.isPresent()) {
            // Expired: free the key in its own short transaction. Deleting inside the
            // effect's transaction would take InnoDB gap locks that deadlock concurrent inserts.
            transactions.inTransaction(() -> {
                idempotency.deleteIfExpired(scope, now);
                return null;
            });
        }
        try {
            return transactions.inTransaction(effect);
        } catch (DuplicateIdempotencyKeyException concurrentRetry) {
            return transactions.readOnly(() -> replay.apply(sameRequest(
                    idempotency.find(scope)
                            .filter(record -> !record.isExpiredAt(now))
                            .orElseThrow(() -> new IllegalStateException("duplicate key without a live record")),
                    requestHash)));
        }
    }

    private static IdempotencyRecord sameRequest(IdempotencyRecord record, String requestHash) {
        if (!MessageDigest.isEqual(
                record.requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new IdempotencyKeyReusedException();
        }
        return record;
    }

    /**
     * SHA-256 (hex) of the request fields exactly as received, one per line.
     * Only this hash is stored, never the fields. {@code null} counts as empty.
     */
    public static String fingerprint(Object... fields) {
        StringBuilder canonical = new StringBuilder("v1");
        for (Object field : fields) {
            canonical.append('\n').append(field == null ? "" : field);
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
