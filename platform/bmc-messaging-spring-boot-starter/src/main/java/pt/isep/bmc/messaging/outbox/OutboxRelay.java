package pt.isep.bmc.messaging.outbox;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs the {@link OutboxPublisher} on its own thread for the life of the
 * application: every poll interval while the outbox is empty, at once while
 * full batches keep coming, and with a growing pause after a failure (2 s
 * doubling up to 5 min by default, without an attempt limit). Neither a broker
 * nor a database outage stops the loop.
 */
public class OutboxRelay implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxPublisher publisher;
    private final Duration pollInterval;
    private final Backoff backoff;
    private volatile Thread thread;

    public OutboxRelay(OutboxPublisher publisher, Duration pollInterval, Duration backoffInitial, Duration backoffMax) {
        this.publisher = publisher;
        this.pollInterval = pollInterval;
        this.backoff = new Backoff(backoffInitial, backoffMax);
    }

    @Override
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        Thread worker = new Thread(this::run, "bmc-outbox");
        worker.setDaemon(true);
        thread = worker;
        worker.start();
    }

    @Override
    public synchronized void stop() {
        Thread worker = thread;
        thread = null;
        if (worker == null) {
            return;
        }
        worker.interrupt();
        try {
            worker.join(Duration.ofSeconds(10).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return thread != null;
    }

    private void run() {
        while (thread == Thread.currentThread() && !Thread.currentThread().isInterrupted()) {
            Duration pause = pass();
            if (pause.isZero()) {
                continue;
            }
            try {
                Thread.sleep(pause);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** One pass; returns how long to wait before the next one. */
    Duration pass() {
        try {
            OutboxPublisher.Pass pass = publisher.publishPending();
            if (!pass.clean()) {
                return backoff.next();
            }
            backoff.reset();
            return pass.moreLikely() ? Duration.ZERO : pollInterval;
        } catch (RuntimeException e) {
            // For example the database is down. The exception class is enough; a message could quote data.
            LOG.warn("Outbox pass failed: {}", e.getClass().getName());
            return backoff.next();
        }
    }
}
