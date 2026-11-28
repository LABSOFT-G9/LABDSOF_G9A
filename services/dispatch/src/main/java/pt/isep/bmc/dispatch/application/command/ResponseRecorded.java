package pt.isep.bmc.dispatch.application.command;

import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;

/**
 * The result of a contractor's answer.
 *
 * @param proposalVersion the intervention's current proposal version after the answer: the new one for a
 *                        counterproposal, the answered one otherwise
 * @param replayed        true when an earlier result was returned for a repeated idempotency key
 */
public record ResponseRecorded(
        InterventionId interventionId, InterventionState state, long version, int proposalVersion,
        boolean replayed) {
}
