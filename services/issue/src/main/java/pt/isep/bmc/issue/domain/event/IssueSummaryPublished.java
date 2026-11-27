package pt.isep.bmc.issue.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.SummaryId;

/**
 * An administrator published or replaced the shared summary of an issue
 * (technical design 4.4). Carries the summary's ID only: the text is read
 * through the API, under authorisation.
 */
public record IssueSummaryPublished(
        IssueId issueId, BuildingId buildingId, long issueVersion, SummaryId summaryId, Instant occurredAt)
        implements DomainEvent {

    public IssueSummaryPublished {
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(summaryId, "summaryId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    @Override
    public String type() {
        return "IssueSummaryPublished";
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
