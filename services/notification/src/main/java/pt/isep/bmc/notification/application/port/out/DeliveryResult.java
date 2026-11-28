package pt.isep.bmc.notification.application.port.out;

/** What a {@link NotificationChannel} did with a notification, as recorded in the delivery log (ADR-007). */
public enum DeliveryResult {

    /** Delivered in the receiving transaction, as the in-app inbox row is. */
    SENT,

    /** Accepted, to be sent after the commit, as an email would be. */
    PENDING;

    /** The delivery log's state token. */
    public String state() {
        return name().toLowerCase();
    }
}
