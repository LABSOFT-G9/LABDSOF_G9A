package pt.isep.bmc.dispatch.application.command;

import java.util.Objects;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/** A cancellation as received (technical design 3.2); validated by the domain. */
public record CancelInterventionCommand(
        UserId actor,
        IdempotencyKey idempotencyKey,
        InterventionId interventionId,
        long expectedVersion,
        String reasonCode,
        String note,
        String correlationId) {

    public CancelInterventionCommand {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(interventionId, "interventionId");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    /** Never prints the note. */
    @Override
    public String toString() {
        return "CancelInterventionCommand[interventionId=" + interventionId + ", reasonCode=" + reasonCode + "]";
    }
}
