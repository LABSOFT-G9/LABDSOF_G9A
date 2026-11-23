package pt.isep.bmc.issue.application.command;

import java.time.Clock;
import pt.isep.bmc.issue.application.port.in.PurgeExpiredIdempotencyKeysUseCase;
import pt.isep.bmc.issue.application.port.out.IdempotencyStore;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;

public final class PurgeExpiredIdempotencyKeysService implements PurgeExpiredIdempotencyKeysUseCase {

    private final TransactionRunner transactions;
    private final IdempotencyStore idempotency;
    private final Clock clock;

    public PurgeExpiredIdempotencyKeysService(TransactionRunner transactions, IdempotencyStore idempotency, Clock clock) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    static final int BATCH_SIZE = 500;

    /** Deletes expired keys in batches, one short transaction per batch. */
    @Override
    public int purge() {
        java.time.Instant now = clock.instant();
        int total = 0;
        int deleted;
        do {
            deleted = transactions.inTransaction(() -> idempotency.deleteExpired(now, BATCH_SIZE));
            total += deleted;
        } while (deleted == BATCH_SIZE);
        return total;
    }
}
