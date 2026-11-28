package pt.isep.bmc.dispatch.domain.event;

import java.time.Instant;
import java.util.UUID;
import pt.isep.bmc.dispatch.domain.intervention.DeclineReason;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/**
 * {@code bmc.dispatch.v1.InterventionDeclined}: the contractor declined, with
 * a reason code and never the contractor's note.
 */
public record InterventionDeclined(
        UUID aggregateId,
        long aggregateVersion,
        BuildingId buildingId,
        Instant occurredAt,
        IssueId issueId,
        DeclineReason reasonCode,
        InterventionState interventionState) implements DomainEvent {

    @Override
    public String type() {
        return "InterventionDeclined";
    }
}
