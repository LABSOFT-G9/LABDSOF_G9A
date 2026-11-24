package pt.isep.bmc.messaging.outbox;

/**
 * One pending outbox row: the event ID, where it goes and the CloudEvents
 * message to send. {@link #toString()} leaves the payload out.
 */
public record OutboxRow(String id, String routingKey, String payload) {

    @Override
    public String toString() {
        return "OutboxRow[id=" + id + ", routingKey=" + routingKey + "]";
    }
}
