package pt.isep.bmc.issue.domain.issue;

import java.time.Instant;
import java.util.List;
import pt.isep.bmc.issue.domain.event.IssueStateChanged;
import pt.isep.bmc.issue.domain.event.IssueSummaryPublished;
import pt.isep.bmc.issue.domain.membership.BuildingAccess;
import pt.isep.bmc.issue.domain.shared.AccessDeniedException;
import pt.isep.bmc.issue.domain.shared.FieldViolation;
import pt.isep.bmc.issue.domain.shared.ForbiddenException;
import pt.isep.bmc.issue.domain.shared.NotPublishedException;
import pt.isep.bmc.issue.domain.shared.ValidationException;

/**
 * Sharing an issue with the building (US-04, technical design 4.2, and the
 * "published shared summary" and "confirm impact" rows of the access matrix).
 *
 * <ul>
 *   <li><b>Publication:</b> only an administrator of the issue's building
 *       publishes or replaces the summary.</li>
 *   <li><b>Impact:</b> only a resident of the building, for themselves, and
 *       only once a summary is published. It never changes the issue.</li>
 *   <li><b>Subscription:</b> anyone who may see the issue, for themselves.</li>
 * </ul>
 *
 * <p>Checks run in a fixed order: a caller who cannot see the issue gets
 * {@link AccessDeniedException} ({@code 404}) before anything else, so its
 * existence is never revealed.
 */
public final class IssueSharing {

    static final String FIELD_SUMMARY = "summary";

    private IssueSharing() {
    }

    /**
     * Publishes a summary. Nothing is produced unless every check passes:
     * visibility ({@code 404}), administration ({@code 403}), the text
     * ({@code 400}), then the version ({@code 409}).
     *
     * @param callerReported whether the caller authored a report of this issue
     * @param summaryId      the ID for the new summary
     * @param sequence       the sequence number for the new history entry
     * @param areaLabel      the label of the issue's area, for {@code IssueStateChanged}
     */
    public static PublicationOutcome publish(
            BuildingAccess access,
            Issue issue,
            boolean callerReported,
            long expectedVersion,
            String rawSummary,
            SummaryId summaryId,
            int sequence,
            String areaLabel,
            Instant now) {
        requireMayPublish(access, issue, callerReported);
        SummaryText.violation(rawSummary).ifPresent(reason -> {
            throw new ValidationException(List.of(new FieldViolation(FIELD_SUMMARY, reason)));
        });

        Issue published = issue.publishSummary(expectedVersion, summaryId, now);
        IssueSummary summary = new IssueSummary(summaryId, published.id(), SummaryText.of(rawSummary), access.userId(),
                published.version(), now);
        return new PublicationOutcome(
                published,
                summary,
                IssueHistoryEntry.summaryPublished(published, sequence),
                List.of(
                        new IssueSummaryPublished(published.id(), published.buildingId(), published.version(), summaryId,
                                now),
                        IssueStateChanged.of(published, areaLabel)));
    }

    /**
     * Whether the caller may publish a summary of the issue: visible
     * ({@code 404}) and an administrator of the building ({@code 403}). Also
     * run before a stored publication receipt is returned again, so a retry is
     * answered by today's permissions.
     */
    public static void requireMayPublish(BuildingAccess access, Issue issue, boolean callerReported) {
        requireVisible(access, issue, callerReported);
        if (!access.administers(issue.buildingId())) {
            throw new ForbiddenException();
        }
    }

    /**
     * Whether the caller may confirm or withdraw their own impact: visible
     * ({@code 404}), a resident of the building ({@code 403}: administrators
     * only see the count), and published ({@code 409}).
     */
    public static void requireMayChangeImpact(BuildingAccess access, Issue issue, boolean callerReported) {
        requireVisible(access, issue, callerReported);
        if (!access.isResidentOf(issue.buildingId())) {
            throw new ForbiddenException();
        }
        if (!issue.isPublished()) {
            throw new NotPublishedException();
        }
    }

    /** Whether the caller may see the issue; the only condition for their own subscription. */
    public static void requireVisible(BuildingAccess access, Issue issue, boolean callerReported) {
        if (!access.canViewIssue(issue.buildingId(), callerReported, issue.isPublished())) {
            throw new AccessDeniedException();
        }
    }
}
