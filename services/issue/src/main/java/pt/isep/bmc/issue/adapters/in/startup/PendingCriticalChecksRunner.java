package pt.isep.bmc.issue.adapters.in.startup;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import pt.isep.bmc.issue.application.port.in.CompletePendingCriticalChecksUseCase;
import pt.isep.bmc.issue.application.port.in.CompletePendingCriticalChecksUseCase.Result;

/**
 * Once per start, after the migrations: issues whose critical check is
 * pending get it now. That covers issues older than the check, and issues
 * whose check failed under an earlier build.
 *
 * <p>The pass runs on its own thread once the application is ready, so the
 * service accepts reports while it works through a long list (US-14:
 * reporting is unaffected). A failure is logged and the next start tries
 * again; so does a pass cut short by a shutdown, because each issue is
 * completed in its own transaction.
 */
public final class PendingCriticalChecksRunner implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PendingCriticalChecksRunner.class);

    private final CompletePendingCriticalChecksUseCase checks;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("pending-critical-checks").daemon(true).factory());
    private final AtomicBoolean started = new AtomicBoolean();
    private final CompletableFuture<Optional<Result>> finished = new CompletableFuture<>();

    public PendingCriticalChecksRunner(CompletePendingCriticalChecksUseCase checks) {
        this.checks = checks;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        start();
    }

    /** Starts the pass in the background and returns at once; a second call does nothing. */
    public void start() {
        if (started.compareAndSet(false, true)) {
            executor.execute(this::run);
        }
    }

    /** The result of this start's pass, or empty when it failed. */
    public CompletableFuture<Optional<Result>> finished() {
        return finished;
    }

    private void run() {
        try {
            Result result = checks.completePending();
            log.info("Pending critical checks: {} completed, {} still pending, {} alert(s) raised",
                    result.completed(), result.stillPending(), result.alertsRaised());
            finished.complete(Optional.of(result));
        } catch (RuntimeException e) {
            log.warn("Pending critical checks were not completed: {}", e.getClass().getName());
            finished.complete(Optional.empty());
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
