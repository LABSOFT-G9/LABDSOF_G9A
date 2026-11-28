package pt.isep.bmc.notification.application.port.in;

import java.util.Optional;
import java.util.UUID;
import pt.isep.bmc.notification.application.query.NotificationPage;
import pt.isep.bmc.notification.domain.shared.UserId;

/** The caller's own notifications, newest first (technical design 4.2, {@code GET /notifications}). */
public interface ListNotificationsQuery {

    int DEFAULT_LIMIT = 20;
    int MAX_LIMIT = 100;

    /**
     * @param caller the token's {@code sub}; nobody can read another user's inbox
     * @param before the {@code nextBefore} of the previous page
     * @param limit  1 to {@value #MAX_LIMIT}
     */
    NotificationPage list(UserId caller, Optional<UUID> before, int limit);
}
