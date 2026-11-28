package pt.isep.bmc.dispatch.application.command;

import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;

/**
 * The result of a saved draft request (technical design annex A 1.3).
 *
 * @param replayed true when an earlier result was returned for a repeated idempotency key
 */
public record DraftReceipt(
        InterventionId interventionId,
        InterventionState state,
        long version,
        int proposalVersion,
        ContractorId contractorId,
        boolean replayed) {
}
