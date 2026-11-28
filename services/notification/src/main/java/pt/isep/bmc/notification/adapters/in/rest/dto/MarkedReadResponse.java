package pt.isep.bmc.notification.adapters.in.rest.dto;

/** {@code POST /notifications/read-all}: how many notifications were unread and are now read. */
public record MarkedReadResponse(int marked) {
}
