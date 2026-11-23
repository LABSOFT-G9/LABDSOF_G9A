package pt.isep.bmc.issue.application.command;

import java.time.Clock;
import java.time.Instant;
import pt.isep.bmc.issue.application.port.in.PurgeExpiredIdempotencyKeysUseCase;
import pt.isep.bmc.issue.application.port.out.IdempotencyStore;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;

/**
 * Deletes expired idempotency keys in batches, one short transaction per
 * batch, so the purge never holds locks that delay live submissions.
 */
public final class PurgeExpiredIdempotencyKeysService implements PurgeExpiredIdempotencyKeysUseCase {

    public static final int DEFAULT_BATCH_SIZE = 500;

    private final TransactionRunner transactions;
    private final IdempotencyStore idempotency;
    private final Clock clock;
    private final int batchSize;

    public PurgeExpiredIdempotencyKeysService(
            TransactionRunner transactions, IdempotencyStore idempotency, Clock clock, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Override
    public int purge() {
        Instant now = clock.instant();
        int total = 0;
        int deleted;
        do {
            deleted = transactions.inTransaction(() -> idempotency.deleteExpired(now, batchSize));
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}
