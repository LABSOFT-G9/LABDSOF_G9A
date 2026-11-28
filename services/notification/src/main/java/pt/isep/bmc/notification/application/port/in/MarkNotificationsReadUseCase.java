package pt.isep.bmc.notification.application.port.in;

import java.util.UUID;
import pt.isep.bmc.notification.domain.shared.UserId;

/**
 * The caller marks notifications of their own inbox read. Reading changes
 * nothing but the read state, and marking twice keeps the first time.
 */
public interface MarkNotificationsReadUseCase {

    /**
     * @throws pt.isep.bmc.notification.application.error.NotFoundException when the
     *         caller has no notification with this ID, including another user's
     */
    void markRead(UserId caller, UUID notificationId);

    /** @return how many notifications were unread and are now read */
    int markAllRead(UserId caller);
}
