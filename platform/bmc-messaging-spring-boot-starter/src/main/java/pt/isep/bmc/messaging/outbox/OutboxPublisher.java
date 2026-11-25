package pt.isep.bmc.messaging.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import pt.isep.bmc.messaging.MessagingMetrics;

/**
 * Sends pending outbox rows to the exchange (ADR-003, item 1).
 *
 * <p>Each message is persistent and published with {@code mandatory} and a
 * correlated publisher confirm. A row is marked {@code sent} only after a
 * positive confirm with no returned message, because the broker also confirms
 * a message that no queue is bound to. Anything else leaves the row
 * {@code pending} and ends the pass, so the order of the outbox is kept and the
 * caller can back off.
 *
 * <p>If the process dies after the broker confirmed and before the row is
 * marked, the row is sent again later: delivery is at least once and consumers
 * deduplicate by event ID. Payloads are never logged.
 */
public class OutboxPublisher {

    /** Structured-mode CloudEvents in JSON (CloudEvents JSON format, section 3). */
    public static final String CONTENT_TYPE = "application/cloudevents+json";

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxStore store;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transaction;
    private final MessagingMetrics metrics;
    private final Clock clock;
    private final String exchange;
    private final int batchSize;
    private final Duration confirmTimeout;
    private final Set<String> notPublishedTypes;

    public OutboxPublisher(
            OutboxStore store,
            RabbitTemplate rabbit,
            TransactionTemplate transaction,
            MessagingMetrics metrics,
            Clock clock,
            String exchange,
            int batchSize,
            Duration confirmTimeout,
            Set<String> notPublishedTypes) {
        this.store = store;
        this.rabbit = rabbit;
        this.transaction = transaction;
        this.metrics = metrics;
        this.clock = clock;
        this.exchange = exchange;
        this.batchSize = batchSize;
        this.confirmTimeout = confirmTimeout;
        this.notPublishedTypes = Set.copyOf(notPublishedTypes);
    }

    /**
     * One pass over the outbox: locks up to a batch of pending rows and sends
     * them in order, stopping at the first one that is not confirmed.
     */
    public Pass publishPending() {
        Pass pass = transaction.execute(status -> {
            List<OutboxRow> rows = store.lockPending(batchSize, notPublishedTypes);
            int sent = 0;
            for (OutboxRow row : rows) {
                if (!send(row)) {
                    store.recordFailedAttempt(row.id());
                    return new Pass(sent, false, false);
                }
                store.markSent(row.id(), clock.instant());
                metrics.published();
                sent++;
            }
            return new Pass(sent, true, rows.size() == batchSize);
        });
        refreshBacklog();
        return pass;
    }

    /** Updates the outbox-lag gauges. */
    public void refreshBacklog() {
        OutboxStore.Backlog backlog = store.backlog(notPublishedTypes, clock.instant());
        metrics.outboxBacklog(backlog.pending(), backlog.oldestAge().toSeconds());
    }

    private boolean send(OutboxRow row) {
        Message message = MessageBuilder.withBody(row.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType(CONTENT_TYPE)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(row.id())
                .build();
        CorrelationData correlation = new CorrelationData(row.id());
        try {
            rabbit.send(exchange, row.routingKey(), message, correlation);
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (correlation.getReturned() != null) {
                metrics.returned();
                LOG.warn("Outbox event {} was returned: no queue is bound to routing key {}", row.id(),
                        row.routingKey());
                return false;
            }
            if (!confirm.ack()) {
                metrics.publishFailed();
                LOG.warn("Outbox event {} ({}) was refused by the broker", row.id(), row.routingKey());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.publishFailed();
            return false;
        } catch (AmqpException | ExecutionException | TimeoutException e) {
            metrics.publishFailed();
            LOG.warn("Outbox event {} ({}) was not confirmed: {}", row.id(), row.routingKey(),
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Result of one pass.
     *
     * @param sent      rows confirmed and marked sent
     * @param clean     false when a publish failed or was returned: the caller should back off
     * @param moreLikely true when the batch was full, so more rows probably wait
     */
    public record Pass(int sent, boolean clean, boolean moreLikely) {
    }
}
