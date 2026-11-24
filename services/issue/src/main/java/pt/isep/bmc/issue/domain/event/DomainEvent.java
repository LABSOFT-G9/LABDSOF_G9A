package pt.isep.bmc.issue.domain.event;

import java.time.Instant;
import java.util.UUID;
import pt.isep.bmc.issue.domain.building.BuildingId;

/**
 * Something that happened in the Issue context and that other services may
 * need (technical design 4.4). Written to the outbox in the same transaction
 * as the change; the CloudEvents envelope is added by the outbox adapter.
 */
public sealed interface DomainEvent
        permits IssueStateChanged, IssueSummaryPublished, IssueTriaged, ReportSubmitted, TriageRequested {

    /** CloudEvents {@code type}, for example {@code ReportSubmitted}. */
    String type();

    /** CloudEvents {@code subject}: the aggregate the event is about. */
    UUID aggregateId();

    long aggregateVersion();

    BuildingId buildingId();

    Instant occurredAt();
}
