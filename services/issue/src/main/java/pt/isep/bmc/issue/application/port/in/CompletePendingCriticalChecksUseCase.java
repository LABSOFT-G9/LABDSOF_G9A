package pt.isep.bmc.issue.application.port.in;

/**
 * Runs the critical check on the issues whose check is still pending: the
 * rule set failed when their report arrived, or they are older than the
 * check. Each issue is tried once per run.
 */
public interface CompletePendingCriticalChecksUseCase {

    Result completePending();

    /**
     * @param completed    checks that ran and are now completed
     * @param stillPending checks that failed again and stay pending
     * @param alertsRaised signals raised by the completed checks
     */
    record Result(int completed, int stillPending, int alertsRaised) {
    }
}
