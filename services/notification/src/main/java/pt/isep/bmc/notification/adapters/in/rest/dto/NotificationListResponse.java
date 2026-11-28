package pt.isep.bmc.notification.adapters.in.rest.dto;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /notifications}.
 *
 * @param nextBefore pass as {@code before} for the next page; {@code null} on the last page
 */
public record NotificationListResponse(List<Item> items, String nextBefore) {

    /**
     * One notification. {@code title} is rendered by the service from the
     * template; {@code templateKey} and {@code params} let the client render
     * its own text instead. {@code link} is a client path such as
     * {@code /issues/<id>}, or {@code null} when there is no page to open.
     * {@code readAt} is {@code null} while unread.
     */
    public record Item(
            String id,
            String templateKey,
            String title,
            String link,
            String priority,
            Map<String, String> params,
            String createdAt,
            boolean read,
            String readAt) {
    }
}
