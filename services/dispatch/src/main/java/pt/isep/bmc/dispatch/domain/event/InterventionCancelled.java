package pt.isep.bmc.dispatch.domain.event;

import java.time.Instant;
import java.util.UUID;
import pt.isep.bmc.dispatch.domain.intervention.CancelReason;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/** {@code bmc.dispatch.v1.InterventionCancelled}, with the reason code and never the note. */
public record InterventionCancelled(
        UUID aggregateId,
        long aggregateVersion,
        BuildingId buildingId,
        Instant occurredAt,
        IssueId issueId,
        CancelReason reasonCode,
        InterventionState interventionState) implements DomainEvent {

    @Override
    public String type() {
        return "InterventionCancelled";
    }
}
