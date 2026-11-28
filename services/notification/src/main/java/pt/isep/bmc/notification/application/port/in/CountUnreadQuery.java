package pt.isep.bmc.notification.application.port.in;

import pt.isep.bmc.notification.domain.shared.UserId;

/** How many of the caller's own notifications are unread, for the client's badge. */
public interface CountUnreadQuery {

    long countUnread(UserId caller);
}
