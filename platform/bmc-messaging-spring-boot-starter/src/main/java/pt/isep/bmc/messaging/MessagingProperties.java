package pt.isep.bmc.messaging;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the shared messaging code. The values are the starting values of
 * technical design 4.6 and ADR-003; they are proposals, not measured results.
 *
 * @param exchange the topic exchange every event is published to
 */
@ConfigurationProperties("bmc.messaging")
public record MessagingProperties(String exchange, Outbox outbox, Inbox inbox) {

    public MessagingProperties {
        exchange = exchange == null ? "bmc.events" : exchange;
        outbox = outbox == null ? new Outbox(false, null, null, null, null, null, null) : outbox;
        inbox = inbox == null ? new Inbox(false, null, null, null, null, null, null, null) : inbox;
    }

    /**
     * The outbox publisher.
     *
     * @param enabled            start the publisher
     * @param pollInterval       pause between two looks at the outbox when nothing is waiting
     * @param batchSize          rows read and locked per look
     * @param confirmTimeout     how long to wait for the broker's confirm of one message
     * @param backoffInitial     first pause after a failed or returned publish
     * @param backoffMax         longest pause; there is no attempt limit
     * @param notPublishedTypes  event types that stay in the outbox and are never sent, because no queue is
     *                           bound to them (a message bound to no queue is returned and would block the rest)
     */
    public record Outbox(
            boolean enabled,
            Duration pollInterval,
            Integer batchSize,
            Duration confirmTimeout,
            Duration backoffInitial,
            Duration backoffMax,
            Set<String> notPublishedTypes) {

        public Outbox {
            pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
            batchSize = batchSize == null ? 50 : batchSize;
            confirmTimeout = confirmTimeout == null ? Duration.ofSeconds(5) : confirmTimeout;
            backoffInitial = backoffInitial == null ? Duration.ofSeconds(2) : backoffInitial;
            backoffMax = backoffMax == null ? Duration.ofMinutes(5) : backoffMax;
            notPublishedTypes = notPublishedTypes == null ? Set.of() : Set.copyOf(notPublishedTypes);
            if (batchSize < 1) {
                throw new IllegalArgumentException("bmc.messaging.outbox.batch-size must be at least 1");
            }
        }
    }

    /**
     * The idempotent consumer.
     *
     * @param enabled                 start the consumer
     * @param queue                   the service's queue, declared by the deployment (for example {@code issue.inbox})
     * @param prefetch                unacknowledged messages the broker may send ahead
     * @param supportedSchemaVersions envelope {@code schemaversion} values the service understands
     * @param retryDelay              pause before a failed message goes back to the queue, so that a short outage
     *                                does not use up the delivery limit at once
     * @param processedRetention      how long processed event IDs are kept (at least 7 days, ADR-003)
     * @param purgeInterval           pause between two clean-ups of processed event IDs
     * @param autoStartup             start consuming when the application starts
     */
    public record Inbox(
            boolean enabled,
            String queue,
            Integer prefetch,
            Set<Integer> supportedSchemaVersions,
            Duration retryDelay,
            Duration processedRetention,
            Duration purgeInterval,
            Boolean autoStartup) {

        public Inbox {
            prefetch = prefetch == null ? 10 : prefetch;
            supportedSchemaVersions =
                    supportedSchemaVersions == null ? Set.of(1) : Set.copyOf(supportedSchemaVersions);
            retryDelay = retryDelay == null ? Duration.ofSeconds(2) : retryDelay;
            processedRetention = processedRetention == null ? Duration.ofDays(7) : processedRetention;
            purgeInterval = purgeInterval == null ? Duration.ofHours(1) : purgeInterval;
            autoStartup = autoStartup == null ? Boolean.TRUE : autoStartup;
            if (enabled && (queue == null || queue.isBlank())) {
                throw new IllegalArgumentException("bmc.messaging.inbox.queue is required when the inbox is enabled");
            }
            if (processedRetention.compareTo(Duration.ofDays(7)) < 0) {
                throw new IllegalArgumentException(
                        "bmc.messaging.inbox.processed-retention must be at least 7 days (ADR-003)");
            }
        }
    }
}
