package pt.isep.bmc.dispatch.domain.intervention;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.shared.FieldViolation;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.UserId;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;

/**
 * Doing the work (US-09, technical design 3.2): the contractor starts it
 * and reports it complete, or an administrator reports it on their behalf
 * with a reason; the administrator can send a report back for rework.
 *
 * <p>The checks run in the order of every other command: who ({@code 404},
 * checked by the caller), the fields ({@code 400}), the intervention's state
 * and version ({@code 409}), then the issue: a resolved or cancelled issue
 * takes no more work ({@code 409} with the issue's state).
 */
public final class InterventionExecution {

    public static final int SUMMARY_MIN = 10;
    public static final int SUMMARY_MAX = 2_000;
    public static final int REASON_MAX = 500;
    private static final Set<String> CLOSED_ISSUE = Set.of("resolved", "cancelled");

    private InterventionExecution() {
    }

    /**
     * A completion report: a work summary (10–2,000 characters) and when the
     * work was done (RFC 3339, not in the future). An administrator reporting
     * for the contractor gives a reason (1–500 characters).
     */
    public static CompletionRequest validateCompletion(
            String summary, String performedAt, String reason, boolean byAdministrator, Instant now) {
        List<FieldViolation> violations = new ArrayList<>();
        Optional<String> text = ProposalFields.text(summary, "summary", SUMMARY_MIN, SUMMARY_MAX, violations);
        Optional<Instant> when = ProposalFields.instant(performedAt, "performedAt", violations);
        if (when.isPresent() && when.get().isAfter(now)) {
            violations.add(new FieldViolation("performedAt", "in_future"));
        }
        Optional<String> why = byAdministrator
                ? ProposalFields.text(reason, "reason", 1, REASON_MAX, violations)
                : Optional.empty();
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        return new CompletionRequest(text.orElseThrow(), when.orElseThrow(), why);
    }

    /** A rework request's reason: required, 1–500 characters. */
    public static String validateRework(String reason) {
        List<FieldViolation> violations = new ArrayList<>();
        Optional<String> why = ProposalFields.text(reason, "reason", 1, REASON_MAX, violations);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        return why.orElseThrow();
    }

    public static Intervention start(Intervention intervention, ProjectedIssue issue, long expectedVersion, Instant now) {
        Intervention started = intervention.start(expectedVersion, now);
        requireOpen(issue);
        return started;
    }

    /**
     * @param number the report's number: one more than the reports already made
     * @param role   {@link CompletionReport#BY_CONTRACTOR} or {@link CompletionReport#BY_ADMINISTRATOR}
     */
    public static CompletionOutcome reportCompletion(
            Intervention intervention, ProjectedIssue issue, CompletionRequest request, String role, int number,
            UserId by, long expectedVersion, Instant now) {
        Intervention reported = intervention.reportCompletion(expectedVersion, now);
        requireOpen(issue);
        CompletionReport report = new CompletionReport(intervention.id(), number, request.summary(),
                request.performedAt(), role, request.reason(), by, now, reported.version(), Optional.empty());
        return new CompletionOutcome(reported, report);
    }

    /** @param latest the report being sent back: the intervention's latest */
    public static CompletionOutcome requestRework(
            Intervention intervention, ProjectedIssue issue, CompletionReport latest, String reason, UserId by,
            long expectedVersion, Instant now) {
        Intervention reworked = intervention.requestRework(expectedVersion, now);
        requireOpen(issue);
        return new CompletionOutcome(reworked, latest.sentBack(reason, by, now));
    }

    private static void requireOpen(ProjectedIssue issue) {
        if (CLOSED_ISSUE.contains(issue.state())) {
            throw new InvalidTransitionException(issue.state());
        }
    }

    public record CompletionRequest(String summary, Instant performedAt, Optional<String> reason) {

        public CompletionRequest {
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(performedAt, "performedAt");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String toString() {
            return "CompletionRequest[]";
        }
    }

    public record CompletionOutcome(Intervention intervention, CompletionReport report) {
    }
}
