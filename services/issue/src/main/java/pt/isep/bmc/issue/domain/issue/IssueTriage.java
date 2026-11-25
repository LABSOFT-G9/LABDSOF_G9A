package pt.isep.bmc.issue.domain.issue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import pt.isep.bmc.issue.domain.event.IssueStateChanged;
import pt.isep.bmc.issue.domain.event.IssueTriaged;
import pt.isep.bmc.issue.domain.membership.BuildingAccess;
import pt.isep.bmc.issue.domain.shared.AccessDeniedException;
import pt.isep.bmc.issue.domain.shared.FieldViolation;
import pt.isep.bmc.issue.domain.shared.ForbiddenException;
import pt.isep.bmc.issue.domain.shared.ValidationException;

/**
 * Manual triage (US-02, technical design 3.1): only an administrator of the
 * issue's building decides category and urgency, with a reason.
 *
 * <p>Checks run in a fixed order, and nothing is produced unless all pass:
 * <ol>
 *   <li>the caller cannot see the issue at all: {@link AccessDeniedException}
 *       ({@code 404}), so its existence is not revealed;</li>
 *   <li>the caller can see it but does not administer the building:
 *       {@link ForbiddenException} ({@code 403});</li>
 *   <li>invalid category, urgency or reason: {@link ValidationException} with
 *       every invalid field;</li>
 *   <li>stale version, then a state that no longer allows triage (see
 *       {@link Issue#decideTriage}).</li>
 * </ol>
 */
public final class IssueTriage {

    static final String FIELD_CATEGORY = "category";
    static final String FIELD_URGENCY = "urgency";
    static final String FIELD_REASON = "reason";

    private IssueTriage() {
    }

    /**
     * @param callerReported whether the caller authored a report of this issue
     * @param sequence       the sequence number for the new history entry
     * @param areaLabel      the label of the issue's area, for {@code IssueStateChanged}
     */
    public static TriageOutcome decide(
            BuildingAccess access,
            Issue issue,
            boolean callerReported,
            long expectedVersion,
            String rawCategory,
            String rawUrgency,
            String rawReason,
            int sequence,
            String areaLabel,
            Instant now) {
        authorise(access, issue, callerReported);

        List<FieldViolation> violations = new ArrayList<>();
        Optional<IssueCategory> category = choice(rawCategory, FIELD_CATEGORY, IssueCategory.fromValue(rawCategory), violations);
        Optional<IssueUrgency> urgency = choice(rawUrgency, FIELD_URGENCY, IssueUrgency.fromValue(rawUrgency), violations);
        TriageReason.violation(rawReason).ifPresent(reason -> violations.add(new FieldViolation(FIELD_REASON, reason)));
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }

        Issue triaged = issue.decideTriage(expectedVersion, category.orElseThrow(), urgency.orElseThrow(), now);
        TriageDecision decision = new TriageDecision(triaged.id(), category.get(), urgency.get(),
                TriageReason.of(rawReason), access.userId(), triaged.version(), now);
        return new TriageOutcome(
                triaged,
                decision,
                IssueHistoryEntry.triageDecided(triaged, sequence),
                List.of(
                        new IssueTriaged(triaged.id(), triaged.buildingId(), triaged.version(), category.get(),
                                urgency.get(), now),
                        IssueStateChanged.of(triaged, areaLabel)));
    }

    /**
     * Checks 1 and 2 on their own: who may decide the triage of {@code issue}
     * and, with that, be told what was decided. Also run before a stored
     * receipt is returned again, so a retry is answered by today's permissions.
     *
     * @param callerReported whether the caller authored a report of this issue
     */
    public static void authorise(BuildingAccess access, Issue issue, boolean callerReported) {
        if (!access.canViewIssue(issue.buildingId(), callerReported)) {
            throw new AccessDeniedException();
        }
        if (!access.administers(issue.buildingId())) {
            throw new ForbiddenException();
        }
    }

    private static <T> Optional<T> choice(String raw, String field, Optional<T> parsed, List<FieldViolation> violations) {
        if (raw == null || raw.isBlank()) {
            violations.add(new FieldViolation(field, "required"));
        } else if (parsed.isEmpty()) {
            violations.add(new FieldViolation(field, "invalid"));
        }
        return parsed;
    }
}
