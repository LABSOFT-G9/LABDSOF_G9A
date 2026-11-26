package pt.isep.bmc.issue.adapters.in.startup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import pt.isep.bmc.issue.application.port.in.CompletePendingCriticalChecksUseCase;

/**
 * Runs once at start, after the migrations: issues whose critical check is
 * pending get it now. That covers issues older than the check, and issues
 * whose check failed under an earlier build. A failure is logged and the
 * service still starts; the next start tries again.
 */
public final class PendingCriticalChecksRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PendingCriticalChecksRunner.class);

    private final CompletePendingCriticalChecksUseCase checks;

    public PendingCriticalChecksRunner(CompletePendingCriticalChecksUseCase checks) {
        this.checks = checks;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            CompletePendingCriticalChecksUseCase.Result result = checks.completePending();
            log.info("Pending critical checks: {} completed, {} still pending, {} alert(s) raised",
                    result.completed(), result.stillPending(), result.alertsRaised());
        } catch (RuntimeException e) {
            log.warn("Pending critical checks were not completed: {}", e.getClass().getName());
        }
    }
}
