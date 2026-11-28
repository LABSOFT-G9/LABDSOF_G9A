package pt.isep.bmc.notification.application.port.out;

import pt.isep.bmc.notification.domain.notification.ChannelId;
import pt.isep.bmc.notification.domain.notification.Notification;

/**
 * One way of delivering a notification (ADR-007): the adapter pattern behind
 * {@link pt.isep.bmc.notification.application.command.NotificationFacade}.
 * Adding a channel means adding one implementation of this port and
 * registering it; the facade, the producers and the event do not change.
 *
 * <p>{@link #deliver} runs inside the transaction that receives the request,
 * and only the first time for each request, recipient and channel: the
 * facade has already claimed the delivery in the
 * {@link DeliveryLog}. A channel that talks to an outside system (SMTP, push)
 * must not call it from here, so that a slow server never holds the message:
 * it answers {@link DeliveryResult#PENDING} and sends after the commit.
 */
public interface NotificationChannel {

    ChannelId id();

    /**
     * Delivers or queues the notification. A failure rolls back the whole
     * request, for every recipient and channel, and the request is retried.
     */
    DeliveryResult deliver(Notification notification);
}
