package pt.isep.bmc.messaging.inbox;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.transaction.support.TransactionTemplate;
import pt.isep.bmc.messaging.CloudEvent;
import pt.isep.bmc.messaging.InvalidEventException;
import pt.isep.bmc.messaging.MessagingMetrics;
import tools.jackson.databind.json.JsonMapper;

/**
 * The idempotent consumer of a service's queue (ADR-003, items 2 and 5).
 *
 * <p>For each message it records the event ID and runs the handler in one
 * database transaction, and acknowledges only after the commit. A repeated
 * delivery finds the ID and is acknowledged without running the handler. A
 * failure rolls back and returns the message to the queue with
 * {@code basic.reject} and requeue; the queue's delivery limit then moves it to
 * the dead-letter queue. A {@code basic.nack} would not do: since RabbitMQ 4.3
 * it does not count towards the limit, and a message that always fails would be
 * redelivered for ever (RabbitMQ quorum queue documentation, "Poison message
 * handling", read 2026-11-24).
 *
 * <p>Rejected to the dead-letter queue without a retry, because a retry cannot
 * help: a body that is not an event envelope, a {@code schemaversion} the
 * service does not support, an envelope whose {@code source} and {@code type}
 * are not the routing key it arrived with (a producer may only publish under
 * its own prefix), a type with no handler, and a {@link RejectEventException}.
 *
 * <p>Payloads and exception messages are never logged.
 */
public class InboxListener implements ChannelAwareMessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(InboxListener.class);

    private final Map<String, EventHandler> handlers;
    private final ProcessedEventStore processedEvents;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final MessagingMetrics metrics;
    private final Clock clock;
    private final Set<Integer> supportedSchemaVersions;
    private final Duration retryDelay;

    public InboxListener(
            Collection<EventHandler> handlers,
            ProcessedEventStore processedEvents,
            TransactionTemplate transaction,
            JsonMapper json,
            MessagingMetrics metrics,
            Clock clock,
            Set<Integer> supportedSchemaVersions,
            Duration retryDelay) {
        Map<String, EventHandler> byType = new HashMap<>();
        for (EventHandler handler : handlers) {
            if (byType.putIfAbsent(handler.eventType(), handler) != null) {
                throw new IllegalStateException("More than one EventHandler for event type " + handler.eventType());
            }
        }
        this.handlers = Map.copyOf(byType);
        this.processedEvents = processedEvents;
        this.transaction = transaction;
        this.json = json;
        this.metrics = metrics;
        this.clock = clock;
        this.supportedSchemaVersions = Set.copyOf(supportedSchemaVersions);
        this.retryDelay = retryDelay;
    }

    @Override
    public void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();

        CloudEvent event;
        EventHandler handler;
        try {
            event = CloudEvent.parse(message.getBody(), json);
            handler = accept(event, routingKey);
        } catch (InvalidEventException e) {
            reject(channel, tag, routingKey, e.getMessage());
            return;
        }

        boolean applied;
        try {
            applied = Boolean.TRUE.equals(transaction.execute(status -> {
                if (!processedEvents.markProcessed(event, clock.instant())) {
                    return false;
                }
                handler.handle(event);
                return true;
            }));
        } catch (RejectEventException e) {
            reject(channel, tag, routingKey, "event " + event.id() + ": " + e.getMessage());
            return;
        } catch (RuntimeException e) {
            metrics.consumeFailed();
            LOG.warn("Event {} ({}) failed and goes back to the queue: {}", event.id(), event.type(),
                    e.getClass().getName());
            pause();
            // basic.reject, not basic.nack: since RabbitMQ 4.3 only a reject counts towards the delivery limit.
            channel.basicReject(tag, true);
            return;
        }

        if (applied) {
            metrics.processed();
        } else {
            metrics.duplicate();
            LOG.debug("Event {} ({}) was delivered again and is ignored", event.id(), event.type());
        }
        channel.basicAck(tag, false);
    }

    private EventHandler accept(CloudEvent event, String routingKey) {
        if (!supportedSchemaVersions.contains(event.schemaVersion())) {
            throw new InvalidEventException(
                    "event " + event.id() + ": schemaversion " + event.schemaVersion() + " is not supported");
        }
        if (!(event.source() + "." + event.type()).equals(routingKey)) {
            throw new InvalidEventException(
                    "event " + event.id() + ": source and type do not match the routing key");
        }
        EventHandler handler = handlers.get(event.type());
        if (handler == null) {
            throw new InvalidEventException("event " + event.id() + ": no handler for type " + event.type());
        }
        return handler;
    }

    private void reject(Channel channel, long tag, String routingKey, String reason) throws IOException {
        metrics.rejected();
        LOG.warn("Message with routing key {} rejected to the dead-letter queue: {}", routingKey, reason);
        channel.basicReject(tag, false);
    }

    private void pause() {
        if (retryDelay.isZero() || retryDelay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(retryDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
