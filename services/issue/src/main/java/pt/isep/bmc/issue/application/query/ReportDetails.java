package pt.isep.bmc.issue.application.query;

import java.time.Instant;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.membership.UserId;
import pt.isep.bmc.issue.domain.report.ReportId;
import pt.isep.bmc.issue.domain.report.ReportSource;
import pt.isep.bmc.issue.domain.report.ReportText;

/**
 * A report with its issue's current state, for a reader already authorised to
 * see the raw text. {@code authorUserId} is used for authorisation and is not
 * returned by the API.
 */
public record ReportDetails(
        ReportId reportId,
        IssueId issueId,
        BuildingId buildingId,
        AreaId areaId,
        String areaLabel,
        UserId authorUserId,
        ReportText text,
        ReportSource source,
        Instant submittedAt,
        IssueState issueState,
        long issueVersion) {
}
