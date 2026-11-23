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

    @Override
    public int purge() {
        return transactions.inTransaction(() -> idempotency.deleteExpired(clock.instant()));
    }
}
