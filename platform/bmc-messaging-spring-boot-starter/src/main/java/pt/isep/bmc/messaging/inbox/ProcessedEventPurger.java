package pt.isep.bmc.messaging.inbox;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Deletes processed event IDs older than the retention (at least 7 days,
 * ADR-003), in small batches so the table is never locked for long.
 */
public class ProcessedEventPurger implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessedEventPurger.class);
    private static final int BATCH = 1_000;

    private final ProcessedEventStore store;
    private final Clock clock;
    private final Duration retention;
    private final Duration interval;
    private ScheduledExecutorService scheduler;

    public ProcessedEventPurger(ProcessedEventStore store, Clock clock, Duration retention, Duration interval) {
        this.store = store;
        this.clock = clock;
        this.retention = retention;
        this.interval = interval;
    }

    /** Deletes everything older than the retention; returns how many IDs were deleted. */
    public int purge() {
        int total = 0;
        int deleted;
        do {
            deleted = store.purgeBefore(clock.instant().minus(retention), BATCH);
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "bmc-processed-events-purge");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::purgeQuietly, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void purgeQuietly() {
        try {
            int deleted = purge();
            if (deleted > 0) {
                LOG.info("Deleted {} processed event IDs older than {}", deleted, retention);
            }
        } catch (RuntimeException e) {
            LOG.warn("Purge of processed event IDs failed: {}", e.getClass().getName());
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }
}
