package pt.isep.bmc.notification.application.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import pt.isep.bmc.notification.domain.notification.Notification;

/**
 * One page of a recipient's inbox, newest first.
 *
 * @param nextBefore pass it as {@code before} for the next page; empty on the last page
 */
public record NotificationPage(List<Notification> items, Optional<UUID> nextBefore) {

    public NotificationPage {
        items = List.copyOf(items);
    }
}
