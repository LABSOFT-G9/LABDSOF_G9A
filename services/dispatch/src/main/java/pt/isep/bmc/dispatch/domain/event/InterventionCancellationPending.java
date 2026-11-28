package pt.isep.bmc.dispatch.domain.event;

import java.time.Instant;
import java.util.UUID;
import pt.isep.bmc.dispatch.domain.intervention.CancelReason;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/** {@code bmc.dispatch.v1.InterventionCancellationPending}: the booking is cancelled at the calendar first. */
public record InterventionCancellationPending(
        UUID aggregateId,
        long aggregateVersion,
        BuildingId buildingId,
        Instant occurredAt,
        IssueId issueId,
        CancelReason reasonCode,
        InterventionState interventionState) implements DomainEvent {

    @Override
    public String type() {
        return "InterventionCancellationPending";
    }
}
