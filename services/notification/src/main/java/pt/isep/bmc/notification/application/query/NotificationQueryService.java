package pt.isep.bmc.notification.application.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import pt.isep.bmc.notification.application.port.in.CountUnreadQuery;
import pt.isep.bmc.notification.application.port.in.ListNotificationsQuery;
import pt.isep.bmc.notification.application.port.out.Inbox;
import pt.isep.bmc.notification.application.port.out.TransactionRunner;
import pt.isep.bmc.notification.domain.notification.Notification;
import pt.isep.bmc.notification.domain.shared.UserId;

/**
 * Reads the caller's inbox. The only filter is the recipient, which is the
 * token's {@code sub}: there is no parameter that names another user, so no
 * request can reach someone else's notifications. A {@code before} cursor
 * from another inbox only bounds the IDs of the caller's own rows.
 */
public final class NotificationQueryService implements ListNotificationsQuery, CountUnreadQuery {

    private final TransactionRunner transactions;
    private final Inbox store;

    public NotificationQueryService(TransactionRunner transactions, Inbox store) {
        this.transactions = transactions;
        this.store = store;
    }

    @Override
    public NotificationPage list(UserId caller, Optional<UUID> before, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be 1 to " + MAX_LIMIT);
        }
        // One extra row tells whether another page follows.
        List<Notification> rows = transactions.readOnly(() -> store.findForRecipient(caller, before, limit + 1));
        if (rows.size() <= limit) {
            return new NotificationPage(rows, Optional.empty());
        }
        List<Notification> page = rows.subList(0, limit);
        return new NotificationPage(page, Optional.of(page.getLast().id()));
    }

    @Override
    public long countUnread(UserId caller) {
        return transactions.readOnly(() -> store.countUnread(caller));
    }
}
