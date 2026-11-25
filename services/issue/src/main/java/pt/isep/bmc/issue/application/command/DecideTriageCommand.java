package pt.isep.bmc.issue.application.command;

import java.util.Objects;
import pt.isep.bmc.issue.application.idempotency.IdempotencyKey;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * An administrator's triage decision as received: category, urgency and
 * reason are unchecked text, validated by the domain.
 *
 * @param expectedVersion the issue version the decision was based on ({@code If-Match})
 */
public record DecideTriageCommand(
        UserId actor,
        IdempotencyKey idempotencyKey,
        IssueId issueId,
        long expectedVersion,
        String category,
        String urgency,
        String reason,
        String correlationId) {

    public DecideTriageCommand {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    /** The reason may quote a private report: never printed. */
    @Override
    public String toString() {
        return "DecideTriageCommand[issueId=" + issueId + ", expectedVersion=" + expectedVersion + "]";
    }
}
