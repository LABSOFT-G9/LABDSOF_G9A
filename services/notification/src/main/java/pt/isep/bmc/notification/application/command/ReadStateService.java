package pt.isep.bmc.notification.application.command;

import java.time.Clock;
import java.util.UUID;
import pt.isep.bmc.notification.application.error.NotFoundException;
import pt.isep.bmc.notification.application.port.in.MarkNotificationsReadUseCase;
import pt.isep.bmc.notification.application.port.out.Inbox;
import pt.isep.bmc.notification.application.port.out.TransactionRunner;
import pt.isep.bmc.notification.domain.shared.UserId;

/**
 * Marks the caller's own notifications read. The caller is the token's
 * {@code sub}; an ID from another inbox answers like an unknown one.
 */
public final class ReadStateService implements MarkNotificationsReadUseCase {

    private final TransactionRunner transactions;
    private final Inbox inbox;
    private final Clock clock;

    public ReadStateService(TransactionRunner transactions, Inbox inbox, Clock clock) {
        this.transactions = transactions;
        this.inbox = inbox;
        this.clock = clock;
    }

    @Override
    public void markRead(UserId caller, UUID notificationId) {
        boolean found = transactions.inTransaction(() -> inbox.markRead(caller, notificationId, clock.instant()));
        if (!found) {
            throw new NotFoundException();
        }
    }

    @Override
    public int markAllRead(UserId caller) {
        return transactions.inTransaction(() -> inbox.markAllRead(caller, clock.instant()));
    }
}
