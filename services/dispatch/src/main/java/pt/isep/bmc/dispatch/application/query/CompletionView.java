package pt.isep.bmc.dispatch.application.query;

import java.time.Instant;
import java.util.Optional;

/**
 * A completion report as an administrator sees it (US-09), kept after a
 * rework request. The contractor sees the same without the administrator's
 * reason for reporting on their behalf.
 */
public record CompletionView(
        int number,
        String summary,
        Instant performedAt,
        String reporterRole,
        Optional<String> reason,
        Instant reportedAt,
        Optional<String> reworkReason,
        Optional<Instant> reworkRequestedAt) {

    /** The contractor's view: no reason of the administrator's. */
    public CompletionView forContractor() {
        return new CompletionView(number, summary, performedAt, reporterRole, Optional.empty(), reportedAt,
                reworkReason, reworkRequestedAt);
    }

    @Override
    public String toString() {
        return "CompletionView[number=" + number + "]";
    }
}
