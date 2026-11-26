package pt.isep.bmc.dispatch.application.command;

import java.util.Objects;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/** New terms by the administrator as received (US-07); validated by the domain. */
public record ChangeTermsCommand(
        UserId actor,
        IdempotencyKey idempotencyKey,
        InterventionId interventionId,
        long expectedVersion,
        String scopeExtract,
        String windowStart,
        String windowEnd,
        String terms) {

    public ChangeTermsCommand {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(interventionId, "interventionId");
    }

    /** Never prints the administrator's text. */
    @Override
    public String toString() {
        return "ChangeTermsCommand[interventionId=" + interventionId + "]";
    }
}
