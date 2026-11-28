package pt.isep.bmc.notification.adapters.in.rest.dto;

/** {@code GET /notifications/unread-count}: the caller's unread notifications. */
public record UnreadCountResponse(long unread) {
}
