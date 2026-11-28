package pt.isep.bmc.dispatch.domain.event;

import java.time.Instant;
import java.util.UUID;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.intervention.ResponseDecision;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/**
 * {@code bmc.dispatch.v1.ContractorResponded}: the assigned contractor
 * accepted or counterproposed. A decline is {@link InterventionDeclined}, so
 * each change writes one event.
 *
 * @param proposalVersion the version answered
 */
public record ContractorResponded(
        UUID aggregateId,
        long aggregateVersion,
        BuildingId buildingId,
        Instant occurredAt,
        IssueId issueId,
        ResponseDecision decision,
        int proposalVersion,
        InterventionState interventionState) implements DomainEvent {

    @Override
    public String type() {
        return "ContractorResponded";
    }
}
