package pt.isep.bmc.notification.application.command;

/**
 * What receiving a request did, counted per recipient and channel.
 *
 * @param delivered  deliveries made now (for the in-app channel: inbox rows added)
 * @param duplicates deliveries that had already been made
 */
public record Receipt(int delivered, int duplicates) {

    /** True when every delivery had already been made: a redelivery or a replay. */
    public boolean isDuplicate() {
        return delivered == 0 && duplicates > 0;
    }
}
