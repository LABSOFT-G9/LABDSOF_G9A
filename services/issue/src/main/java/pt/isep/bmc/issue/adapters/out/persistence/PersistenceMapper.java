package pt.isep.bmc.issue.adapters.out.persistence;

import java.util.UUID;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.AreaKind;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.membership.Membership;
import pt.isep.bmc.issue.domain.membership.MembershipId;
import pt.isep.bmc.issue.domain.membership.MembershipRole;
import pt.isep.bmc.issue.domain.membership.MembershipStatus;
import pt.isep.bmc.issue.domain.membership.UserId;
import pt.isep.bmc.issue.domain.report.Report;
import pt.isep.bmc.issue.domain.report.ReportId;
import pt.isep.bmc.issue.domain.report.ReportSource;
import pt.isep.bmc.issue.domain.report.ReportText;

/** Maps between JPA entities and domain objects. */
final class PersistenceMapper {

    private PersistenceMapper() {
    }

    static IssueEntity toEntity(Issue issue) {
        IssueEntity entity = new IssueEntity();
        entity.id = issue.id().toString();
        entity.buildingId = issue.buildingId().toString();
        entity.areaId = issue.areaId().toString();
        entity.state = issue.state().value();
        entity.version = issue.version();
        entity.criticalCheckStatus = issue.criticalCheckStatus().value();
        entity.createdAt = Timestamps.toColumn(issue.createdAt());
        entity.updatedAt = entity.createdAt;
        return entity;
    }

    static ReportEntity toEntity(Report report) {
        ReportEntity entity = new ReportEntity();
        entity.id = report.id().toString();
        entity.issueId = report.issueId().toString();
        entity.buildingId = report.buildingId().toString();
        entity.areaId = report.areaId().toString();
        entity.authorUserId = report.authorUserId().value();
        entity.text = report.text().value();
        entity.source = report.source().value();
        entity.submittedAt = Timestamps.toColumn(report.submittedAt());
        return entity;
    }

    static Report toDomain(ReportEntity entity) {
        return Report.restore(
                new ReportId(UUID.fromString(entity.id)),
                new IssueId(UUID.fromString(entity.issueId)),
                new BuildingId(UUID.fromString(entity.buildingId)),
                new AreaId(UUID.fromString(entity.areaId)),
                new UserId(entity.authorUserId),
                ReportText.restore(entity.text),
                ReportSource.fromValue(entity.source),
                Timestamps.fromColumn(entity.submittedAt));
    }

    static IssueHistoryEntity toEntity(IssueHistoryEntry entry) {
        IssueHistoryEntity entity = new IssueHistoryEntity();
        entity.issueId = entry.issueId().toString();
        entity.sequence = entry.sequence();
        entity.type = entry.type().value();
        entity.visibility = entry.visibility().value();
        entity.actorRole = entry.actorRole();
        entity.occurredAt = Timestamps.toColumn(entry.occurredAt());
        return entity;
    }

    static Area toDomain(AreaEntity entity) {
        return new Area(
                new AreaId(UUID.fromString(entity.id)),
                new BuildingId(UUID.fromString(entity.buildingId)),
                entity.label,
                AreaKind.fromValue(entity.kind));
    }

    static Membership toDomain(MembershipEntity entity) {
        return new Membership(
                new MembershipId(UUID.fromString(entity.id)),
                new UserId(entity.userId),
                new BuildingId(UUID.fromString(entity.buildingId)),
                MembershipRole.fromValue(entity.role),
                MembershipStatus.fromValue(entity.status),
                Timestamps.fromColumn(entity.validFrom),
                Timestamps.fromColumn(entity.validUntil),
                entity.version);
    }
}
