package pt.isep.bmc.notification.application.port.out;

import java.time.Instant;
import pt.isep.bmc.notification.domain.notification.ChannelId;
import pt.isep.bmc.notification.domain.notification.Notification;

/**
 * The delivery log (technical design 2.3, ADR-007): one row per request,
 * recipient and channel. It is the idempotency gate for every channel. Runs on
 * the caller's transaction.
 */
public interface DeliveryLog {

    /**
     * Claims the delivery of the notification's request to its recipient on
     * this channel. Concurrent or repeated claims of the same request,
     * recipient and channel succeed once in total: a second transaction waits
     * for the first and then gets {@code false}.
     *
     * @return whether this call claimed it; {@code false} when it was already delivered or queued
     */
    boolean claim(Notification notification, ChannelId channel, Instant now);

    /** Records what the channel did with a delivery this transaction claimed. */
    void record(Notification notification, ChannelId channel, DeliveryResult result, Instant now);
}
