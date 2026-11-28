package pt.isep.bmc.dispatch.application.command;

import java.util.Objects;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/** A manual booking confirmation as received (US-08); validated by the domain. */
public record ManualConfirmationCommand(
        UserId actor,
        IdempotencyKey idempotencyKey,
        InterventionId interventionId,
        long expectedVersion,
        String windowStart,
        String windowEnd,
        String source,
        String reason,
        String correlationId) {

    public ManualConfirmationCommand {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(interventionId, "interventionId");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    /** Never prints the reason. */
    @Override
    public String toString() {
        return "ManualConfirmationCommand[interventionId=" + interventionId + "]";
    }
}
