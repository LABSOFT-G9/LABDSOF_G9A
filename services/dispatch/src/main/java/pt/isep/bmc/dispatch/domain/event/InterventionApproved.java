package pt.isep.bmc.dispatch.domain.event;

import java.time.Instant;
import java.util.UUID;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * {@code bmc.dispatch.v1.InterventionApproved}: one proposal version was
 * approved and the request sent. {@code contractorUserId} lets Issue address
 * the contractor's notification; it grants no read access in Issue.
 */
public record InterventionApproved(
        UUID aggregateId,
        long aggregateVersion,
        BuildingId buildingId,
        Instant occurredAt,
        IssueId issueId,
        UserId contractorUserId,
        String contractorDisplayName,
        int approvedProposalVersion,
        InterventionState interventionState) implements DomainEvent {

    @Override
    public String type() {
        return "InterventionApproved";
    }
}
