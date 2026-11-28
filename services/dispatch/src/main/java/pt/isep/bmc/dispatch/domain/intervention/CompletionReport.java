package pt.isep.bmc.dispatch.domain.intervention;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * A report that the work was done (US-09). Kept after a rework request, so
 * the history shows every report and why it was sent back. Costs belong to
 * US-11.
 *
 * @param number              1 for the first report of the intervention
 * @param reporterRole        {@link #BY_CONTRACTOR} or {@link #BY_ADMINISTRATOR}
 * @param reason              why an administrator reported it on the contractor's behalf; required then
 * @param interventionVersion the intervention's version after the report
 * @param rework              set when the administrator sent this report back
 */
public record CompletionReport(
        InterventionId interventionId,
        int number,
        String summary,
        Instant performedAt,
        String reporterRole,
        Optional<String> reason,
        UserId reportedBy,
        Instant reportedAt,
        long interventionVersion,
        Optional<Rework> rework) {

    public static final String BY_CONTRACTOR = "contractor";
    public static final String BY_ADMINISTRATOR = "administrator";

    public CompletionReport {
        Objects.requireNonNull(interventionId, "interventionId");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(performedAt, "performedAt");
        Objects.requireNonNull(reporterRole, "reporterRole");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(reportedBy, "reportedBy");
        Objects.requireNonNull(reportedAt, "reportedAt");
        Objects.requireNonNull(rework, "rework");
        if (BY_ADMINISTRATOR.equals(reporterRole) != reason.isPresent()) {
            throw new IllegalArgumentException("an administrator's report has a reason, and only theirs");
        }
    }

    public CompletionReport sentBack(String reworkReason, UserId by, Instant at) {
        if (rework.isPresent()) {
            throw new IllegalStateException("a report is sent back once");
        }
        return new CompletionReport(interventionId, number, summary, performedAt, reporterRole, reason, reportedBy,
                reportedAt, interventionVersion, Optional.of(new Rework(reworkReason, by, at)));
    }

    /** Never prints the summary or the reasons. */
    @Override
    public String toString() {
        return "CompletionReport[interventionId=" + interventionId + ", number=" + number + "]";
    }

    /** The administrator sent the report back for rework, with a reason. */
    public record Rework(String reason, UserId requestedBy, Instant requestedAt) {

        public Rework {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(requestedBy, "requestedBy");
            Objects.requireNonNull(requestedAt, "requestedAt");
        }

        @Override
        public String toString() {
            return "Rework[]";
        }
    }
}
