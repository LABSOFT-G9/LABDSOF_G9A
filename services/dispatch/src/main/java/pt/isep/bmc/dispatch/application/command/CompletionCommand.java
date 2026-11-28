package pt.isep.bmc.dispatch.application.command;

import java.util.Objects;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/** A completion report as received (US-09); validated by the domain. */
public record CompletionCommand(
        UserId actor,
        IdempotencyKey idempotencyKey,
        InterventionId interventionId,
        long expectedVersion,
        String summary,
        String performedAt,
        String reason,
        String correlationId) {

    public CompletionCommand {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(interventionId, "interventionId");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    /** Never prints the summary or the reason. */
    @Override
    public String toString() {
        return "CompletionCommand[interventionId=" + interventionId + "]";
    }
}
