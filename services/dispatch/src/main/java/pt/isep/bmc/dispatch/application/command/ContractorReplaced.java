package pt.isep.bmc.dispatch.application.command;

import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;

/**
 * The result of a replaced contractor.
 *
 * @param version  the intervention's version after the change ({@code ETag})
 * @param replayed true when an earlier result was returned for a repeated idempotency key
 */
public record ContractorReplaced(
        InterventionId interventionId, InterventionState state, long version, ContractorId contractorId,
        boolean replayed) {
}
