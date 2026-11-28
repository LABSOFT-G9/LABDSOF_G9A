package pt.isep.bmc.dispatch.application.command;

import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;

/**
 * The result of an approval: {@code sent}, or {@code scheduling_pending} for
 * a contractor's counterproposal.
 *
 * @param replayed true when an earlier result was returned for a repeated idempotency key
 */
public record ProposalApproved(
        InterventionId interventionId, InterventionState state, long version, int approvedProposalVersion,
        boolean replayed) {
}
