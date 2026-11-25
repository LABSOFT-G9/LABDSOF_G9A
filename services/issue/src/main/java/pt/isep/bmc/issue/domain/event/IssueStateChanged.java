package pt.isep.bmc.issue.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;

/**
 * The issue's current state, for Dispatch's issue projection (technical
 * design 4.3 and 4.4). Carries the full new state, so a receiver applies it by
 * version and never needs an earlier event. No raw text: the area label names
 * a place, not a person.
 *
 * @param dispatchable whether an intervention may be created for the issue now
 */
public record IssueStateChanged(
        IssueId issueId,
        BuildingId buildingId,
        IssueState state,
        long issueVersion,
        boolean dispatchable,
        Optional<IssueCategory> category,
        Optional<IssueUrgency> urgency,
        String areaLabel,
        Instant occurredAt)
        implements DomainEvent {

    public IssueStateChanged {
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(urgency, "urgency");
        Objects.requireNonNull(areaLabel, "areaLabel");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** The event for the issue as it is now. */
    public static IssueStateChanged of(Issue issue, String areaLabel) {
        return new IssueStateChanged(issue.id(), issue.buildingId(), issue.state(), issue.version(),
                issue.state().isDispatchable(), issue.category(), issue.urgency(), areaLabel, issue.updatedAt());
    }

    @Override
    public String type() {
        return "IssueStateChanged";
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
