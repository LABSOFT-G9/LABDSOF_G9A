package pt.isep.bmc.issue.domain.event;

import java.time.Instant;
import java.util.UUID;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;

/**
 * An administrator decided an issue's category and urgency (technical design
 * 4.4). Always a human decision; never carries the reason.
 */
public record IssueTriaged(
        IssueId issueId,
        BuildingId buildingId,
        long issueVersion,
        IssueCategory category,
        IssueUrgency urgency,
        Instant occurredAt)
        implements DomainEvent {

    @Override
    public String type() {
        return "IssueTriaged";
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
