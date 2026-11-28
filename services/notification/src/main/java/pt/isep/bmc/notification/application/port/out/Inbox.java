package pt.isep.bmc.notification.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import pt.isep.bmc.notification.domain.notification.Notification;
import pt.isep.bmc.notification.domain.shared.UserId;

/**
 * The in-app inbox that the in-app channel writes: reading it, and its read
 * state. Every method is scoped to one recipient, so none can touch another
 * user's notifications. Runs on the caller's transaction.
 */
public interface Inbox {

    /**
     * The recipient's notifications, newest first.
     *
     * @param before only notifications older than this ID (the previous page's last item)
     */
    List<Notification> findForRecipient(UserId recipient, Optional<UUID> before, int limit);

    /** How many of the recipient's notifications are unread. */
    long countUnread(UserId recipient);

    /**
     * Marks one of the recipient's notifications read, keeping the first time
     * it was read.
     *
     * @return {@code false} when the recipient has no notification with this ID
     */
    boolean markRead(UserId recipient, UUID notificationId, Instant now);

    /** Marks every unread notification of the recipient read; returns how many changed. */
    int markAllRead(UserId recipient, Instant now);
}
