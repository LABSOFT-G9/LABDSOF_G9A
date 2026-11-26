package pt.isep.bmc.dispatch.application.command;

import java.util.Objects;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * The contractor's answer as received (US-07); every field after the
 * intervention is validated by the domain.
 *
 * @param expectedVersion the intervention version the contractor was looking at ({@code If-Match})
 */
public record RespondCommand(
        UserId actor,
        IdempotencyKey idempotencyKey,
        InterventionId interventionId,
        long expectedVersion,
        Integer proposalVersion,
        String decision,
        String reasonCode,
        String note,
        String windowStart,
        String windowEnd,
        String terms,
        String costAmount,
        String costCurrency,
        String correlationId) {

    public RespondCommand {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(interventionId, "interventionId");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    /** Never prints the contractor's text. */
    @Override
    public String toString() {
        return "RespondCommand[interventionId=" + interventionId + ", decision=" + decision + "]";
    }
}
