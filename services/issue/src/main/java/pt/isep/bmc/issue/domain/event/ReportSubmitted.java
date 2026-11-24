package pt.isep.bmc.issue.domain.event;

import java.time.Instant;
import java.util.UUID;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.report.ReportId;

/** A report was stored (technical design 4.4). Carries IDs only, never text. */
public record ReportSubmitted(
        ReportId reportId, IssueId issueId, AreaId areaId, BuildingId buildingId, long issueVersion, Instant occurredAt)
        implements DomainEvent {

    @Override
    public String type() {
        return "ReportSubmitted";
    }

    @Override
    public UUID aggregateId() {
        return issueId.value();
    }

    @Override
    public long aggregateVersion() {
        return issueVersion;
    }
}
