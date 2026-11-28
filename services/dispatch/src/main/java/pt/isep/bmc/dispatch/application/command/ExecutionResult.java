package pt.isep.bmc.dispatch.application.command;

import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;

/** @param replayed true when an earlier result was returned for a repeated idempotency key */
public record ExecutionResult(InterventionId interventionId, InterventionState state, long version, boolean replayed) {
}
