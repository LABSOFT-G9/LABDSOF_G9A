package pt.isep.bmc.issue.application.port.out;

import java.time.Instant;
import java.util.Optional;

/**
 * Storage of idempotency keys (technical design 3.3 and 4.1).
 *
 * <p>{@link #record} runs inside the transaction that performs the effect and
 * must fail immediately with {@link DuplicateIdempotencyKeyException} when the
 * scope is already stored. With a unique key in the database, a concurrent
 * retry waits for the first transaction and then fails, so exactly one effect
 * commits.
 */
public interface IdempotencyStore {

    Optional<IdempotencyRecord> find(IdempotencyScope scope);

    void record(IdempotencyRecord record);

    /**
     * Deletes the scope's record if it expired at {@code now}. Call it in its
     * own transaction, never in the one that records the key.
     */
    void deleteIfExpired(IdempotencyScope scope, Instant now);

    /**
     * Deletes at most {@code limit} records expired at {@code now}, oldest
     * first; returns how many. Small batches keep each purge transaction short,
     * so its locks never hold up live inserts for long.
     */
    int deleteExpired(Instant now, int limit);
}
