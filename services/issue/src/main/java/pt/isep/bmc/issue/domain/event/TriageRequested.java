package pt.isep.bmc.issue.domain.event;

import java.time.Instant;
import java.util.UUID;
import pt.isep.bmc.issue.domain.building.AreaKind;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.report.ReportText;

/**
 * Ask the AI triage service for a suggestion (technical design 4.4). The only
 * event that carries report text, bound point-to-point to
 * {@code ai-triage.requests}; never logged. {@link #toString()} redacts it.
 */
public record TriageRequested(
        IssueId issueId, long issueVersion, BuildingId buildingId, AreaKind areaKind, ReportText text, Instant occurredAt)
        implements DomainEvent {

    @Override
    public String type() {
        return "TriageRequested";
    }

    @Override
    public UUID aggregateId() {
        return issueId.value();
    }

    @Override
    public long aggregateVersion() {
        return issueVersion;
    }

    @Override
    public String toString() {
        return "TriageRequested[issueId=" + issueId + ", issueVersion=" + issueVersion + ", text=redacted]";
    }
}
