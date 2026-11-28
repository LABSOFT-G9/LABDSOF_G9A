package pt.isep.bmc.dispatch.domain.event;

import java.time.Instant;
import java.util.UUID;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/** {@code bmc.dispatch.v1.CompletionReported}: the work was reported complete. Not a closure; the summary stays in Dispatch. */
public record CompletionReported(
        UUID aggregateId,
        long aggregateVersion,
        BuildingId buildingId,
        Instant occurredAt,
        IssueId issueId,
        InterventionState interventionState) implements DomainEvent {

    @Override
    public String type() {
        return "CompletionReported";
    }
}
