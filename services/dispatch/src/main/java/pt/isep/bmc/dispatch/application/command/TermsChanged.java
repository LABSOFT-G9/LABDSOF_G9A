package pt.isep.bmc.dispatch.application.command;

import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;

/**
 * The result of changed terms: {@code awaiting_approval} with the new version.
 *
 * @param replayed true when an earlier result was returned for a repeated idempotency key
 */
public record TermsChanged(
        InterventionId interventionId, InterventionState state, long version, int proposalVersion,
        boolean replayed) {
}
