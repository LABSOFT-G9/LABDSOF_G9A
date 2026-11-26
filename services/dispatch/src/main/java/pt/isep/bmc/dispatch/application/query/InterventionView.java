package pt.isep.bmc.dispatch.application.query;

import java.time.Instant;
import java.util.Objects;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/**
 * An intervention as an administrator of its building sees it: the selected
 * contractor and the current proposal version. Never the report text: the
 * scope extract is the administrator's own text.
 */
public record InterventionView(
        InterventionId interventionId,
        IssueId issueId,
        BuildingId buildingId,
        Contractor contractor,
        InterventionState state,
        long version,
        Proposal proposal,
        Instant createdAt,
        Instant updatedAt) {

    public InterventionView {
        Objects.requireNonNull(interventionId, "interventionId");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(contractor, "contractor");
        Objects.requireNonNull(proposal, "proposal");
    }

    public record Contractor(ContractorId contractorId, String displayName, boolean synthetic) {
    }

    public record Proposal(int proposalVersion, String authorRole, String scopeExtract, TimeWindow window, String terms,
            Instant createdAt) {
    }
}
