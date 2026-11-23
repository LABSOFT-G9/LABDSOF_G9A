package pt.isep.bmc.issue.application.query;

import java.time.Instant;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.membership.UserId;
import pt.isep.bmc.issue.domain.report.ReportId;
import pt.isep.bmc.issue.domain.report.ReportSource;

/** One row of a report list. Holds no text: lists expose as little as possible. */
public record ReportSummary(
        ReportId reportId,
        IssueId issueId,
        BuildingId buildingId,
        AreaId areaId,
        String areaLabel,
        UserId authorUserId,
        ReportSource source,
        Instant submittedAt,
        IssueState issueState,
        long issueVersion) {
}
