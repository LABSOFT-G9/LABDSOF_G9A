package pt.isep.bmc.dispatch.application.command;

import java.util.Objects;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/** A rework request as received (US-09); validated by the domain. */
public record ReworkCommand(
        UserId actor, IdempotencyKey idempotencyKey, InterventionId interventionId, long expectedVersion, String reason,
        String correlationId) {

    public ReworkCommand {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(interventionId, "interventionId");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    /** Never prints the reason. */
    @Override
    public String toString() {
        return "ReworkCommand[interventionId=" + interventionId + "]";
    }
}
