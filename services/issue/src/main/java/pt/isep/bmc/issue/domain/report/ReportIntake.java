package pt.isep.bmc.issue.domain.report;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.event.ReportSubmitted;
import pt.isep.bmc.issue.domain.event.TriageRequested;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.membership.BuildingAccess;
import pt.isep.bmc.issue.domain.shared.AccessDeniedException;
import pt.isep.bmc.issue.domain.shared.InvalidAreaException;

/**
 * Domain service for {@code POST /reports} (technical design 3.1, first row).
 * Checks, in this order, so that nothing about other buildings is revealed:
 *
 * <ol>
 *   <li>the caller may report in the building, otherwise
 *       {@link AccessDeniedException} (answered {@code 404});</li>
 *   <li>the text is valid, otherwise {@code ValidationException};</li>
 *   <li>the area exists in that building, otherwise
 *       {@link InvalidAreaException}.</li>
 * </ol>
 *
 * <p>Nothing is produced unless every check passes, so a rejected request
 * leaves no partial report. No AI is involved: triage is requested through an
 * event and never awaited.
 */
public final class ReportIntake {

    private ReportIntake() {
    }

    /**
     * @param area the area looked up by {@code areaId}, or empty when unknown
     */
    public static ReportSubmission submit(
            BuildingAccess access,
            BuildingId buildingId,
            AreaId areaId,
            Optional<Area> area,
            String rawText,
            ReportId reportId,
            IssueId issueId,
            Instant now) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(areaId, "areaId");
        Objects.requireNonNull(area, "area");

        ReportSource source = access.reportSourceFor(buildingId).orElseThrow(AccessDeniedException::new);
        ReportText text = ReportText.of(rawText);
        Area selected = area
                .filter(a -> a.id().equals(areaId) && a.belongsTo(buildingId))
                .orElseThrow(InvalidAreaException::new);

        Issue issue = Issue.receive(issueId, buildingId, selected.id(), now);
        Report report = Report.submit(reportId, issueId, buildingId, selected.id(), access.userId(), text, source, now);
        IssueHistoryEntry history = IssueHistoryEntry.reportReceived(issueId, source, now);
        return new ReportSubmission(
                issue,
                report,
                history,
                List.of(
                        new ReportSubmitted(reportId, issueId, selected.id(), buildingId, issue.version(), now),
                        new TriageRequested(issueId, issue.version(), buildingId, selected.kind(), text, now)));
    }
}
