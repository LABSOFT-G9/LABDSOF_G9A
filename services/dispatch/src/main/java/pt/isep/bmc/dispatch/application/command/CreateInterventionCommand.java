package pt.isep.bmc.dispatch.application.command;

import java.util.Objects;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * A draft request as received (US-06): every field except the issue is
 * unchecked and validated by the domain, so all invalid fields are reported
 * together.
 */
public record CreateInterventionCommand(
        UserId actor,
        IdempotencyKey idempotencyKey,
        IssueId issueId,
        String contractorId,
        String scopeExtract,
        String windowStart,
        String windowEnd,
        String terms) {

    public CreateInterventionCommand {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(issueId, "issueId");
    }

    /** Never prints the administrator's text. */
    @Override
    public String toString() {
        return "CreateInterventionCommand[issueId=" + issueId + "]";
    }
}
