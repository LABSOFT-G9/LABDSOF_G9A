package pt.isep.bmc.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters and gauges of the outbox and the inbox (ADR-003, item 7). Without a
 * {@link MeterRegistry} the values are still kept, so tests can read them.
 */
public final class MessagingMetrics {

    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxOldestPendingSeconds = new AtomicLong();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong returned = new AtomicLong();
    private final AtomicLong publishFailures = new AtomicLong();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong consumeFailures = new AtomicLong();

    private final Counter publishedCounter;
    private final Counter returnedCounter;
    private final Counter publishFailuresCounter;
    private final Counter processedCounter;
    private final Counter duplicatesCounter;
    private final Counter rejectedCounter;
    private final Counter consumeFailuresCounter;

    public MessagingMetrics(MeterRegistry registry) {
        if (registry == null) {
            publishedCounter = null;
            returnedCounter = null;
            publishFailuresCounter = null;
            processedCounter = null;
            duplicatesCounter = null;
            rejectedCounter = null;
            consumeFailuresCounter = null;
            return;
        }
        Gauge.builder("bmc.outbox.pending", outboxPending, AtomicLong::get)
                .description("Outbox rows waiting to be published")
                .register(registry);
        Gauge.builder("bmc.outbox.oldest.pending.seconds", outboxOldestPendingSeconds, AtomicLong::get)
                .description("Age of the oldest outbox row waiting to be published (outbox lag)")
                .baseUnit("seconds")
                .register(registry);
        publishedCounter = counter(registry, "bmc.outbox.published", "Events confirmed by the broker");
        returnedCounter = counter(registry, "bmc.outbox.returned", "Events returned because no queue is bound");
        publishFailuresCounter =
                counter(registry, "bmc.outbox.failures", "Publish attempts that failed or were not confirmed");
        processedCounter = counter(registry, "bmc.inbox.processed", "Events applied once");
        duplicatesCounter = counter(registry, "bmc.inbox.duplicates", "Repeated deliveries acknowledged without effect");
        rejectedCounter = counter(registry, "bmc.inbox.rejected", "Events sent to the dead-letter queue without retry");
        consumeFailuresCounter = counter(registry, "bmc.inbox.failures", "Deliveries that failed and went back to the queue");
    }

    private static Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    private static void count(AtomicLong value, Counter counter) {
        value.incrementAndGet();
        if (counter != null) {
            counter.increment();
        }
    }

    public void outboxBacklog(long pending, long oldestPendingSeconds) {
        outboxPending.set(pending);
        outboxOldestPendingSeconds.set(oldestPendingSeconds);
    }

    public void published() {
        count(published, publishedCounter);
    }

    public void returned() {
        count(returned, returnedCounter);
    }

    public void publishFailed() {
        count(publishFailures, publishFailuresCounter);
    }

    public void processed() {
        count(processed, processedCounter);
    }

    public void duplicate() {
        count(duplicates, duplicatesCounter);
    }

    public void rejected() {
        count(rejected, rejectedCounter);
    }

    public void consumeFailed() {
        count(consumeFailures, consumeFailuresCounter);
    }

    public long outboxPending() {
        return outboxPending.get();
    }

    public long outboxOldestPendingSeconds() {
        return outboxOldestPendingSeconds.get();
    }

    public long publishedCount() {
        return published.get();
    }

    public long returnedCount() {
        return returned.get();
    }

    public long publishFailureCount() {
        return publishFailures.get();
    }

    public long processedCount() {
        return processed.get();
    }

    public long duplicateCount() {
        return duplicates.get();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    public long consumeFailureCount() {
        return consumeFailures.get();
    }
}
