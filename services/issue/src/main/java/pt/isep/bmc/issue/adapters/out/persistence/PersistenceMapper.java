package pt.isep.bmc.issue.adapters.out.persistence;

import java.util.UUID;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.AreaKind;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.CriticalCheckStatus;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;
import pt.isep.bmc.issue.domain.issue.SuggestionOrigin;
import pt.isep.bmc.issue.domain.issue.SummaryId;
import pt.isep.bmc.issue.domain.issue.TriageDecision;
import pt.isep.bmc.issue.domain.issue.TriageReason;
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
        entity.category = issue.category().map(IssueCategory::value).orElse(null);
        entity.urgency = issue.urgency().map(IssueUrgency::value).orElse(null);
        entity.criticalCheckStatus = issue.criticalCheckStatus().value();
        entity.criticalRulesVersion = issue.criticalRulesVersion().orElse(null);
        entity.publishedSummaryId = issue.publishedSummaryId().map(SummaryId::toString).orElse(null);
        entity.createdAt = Timestamps.toColumn(issue.createdAt());
        entity.updatedAt = Timestamps.toColumn(issue.updatedAt());
        return entity;
    }

    static Issue toDomain(IssueEntity entity) {
        return Issue.restore(
                new IssueId(UUID.fromString(entity.id)),
                new BuildingId(UUID.fromString(entity.buildingId)),
                new AreaId(UUID.fromString(entity.areaId)),
                IssueState.fromValue(entity.state),
                entity.version,
                entity.category == null ? null : category(entity.category),
                entity.urgency == null ? null : urgency(entity.urgency),
                CriticalCheckStatus.fromValue(entity.criticalCheckStatus),
                entity.criticalRulesVersion,
                entity.publishedSummaryId == null ? null : new SummaryId(UUID.fromString(entity.publishedSummaryId)),
                Timestamps.fromColumn(entity.createdAt),
                Timestamps.fromColumn(entity.updatedAt));
    }

    static TriageDecisionEntity toEntity(TriageDecision decision) {
        TriageDecisionEntity entity = new TriageDecisionEntity();
        entity.issueId = decision.issueId().toString();
        entity.resultingVersion = decision.resultingVersion();
        entity.category = decision.category().value();
        entity.urgency = decision.urgency().value();
        entity.reason = decision.reason().value();
        entity.decidedBy = decision.decidedBy().value();
        entity.decidedAt = Timestamps.toColumn(decision.decidedAt());
        return entity;
    }

    static TriageDecision toDomain(TriageDecisionEntity entity) {
        return new TriageDecision(
                new IssueId(UUID.fromString(entity.issueId)),
                category(entity.category),
                urgency(entity.urgency),
                TriageReason.restore(entity.reason),
                new UserId(entity.decidedBy),
                entity.resultingVersion,
                Timestamps.fromColumn(entity.decidedAt));
    }

    static IssueCategory category(String stored) {
        return IssueCategory.fromValue(stored)
                .orElseThrow(() -> new IllegalStateException("unknown stored category"));
    }

    static IssueUrgency urgency(String stored) {
        return IssueUrgency.fromValue(stored)
                .orElseThrow(() -> new IllegalStateException("unknown stored urgency"));
    }

    static SuggestionOrigin origin(String stored) {
        return SuggestionOrigin.fromValue(stored)
                .orElseThrow(() -> new IllegalStateException("unknown stored suggestion origin"));
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
        entity.issueVersion = entry.issueVersion();
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
