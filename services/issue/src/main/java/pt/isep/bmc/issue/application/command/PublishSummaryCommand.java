package pt.isep.bmc.issue.application.command;

import java.util.Objects;
import pt.isep.bmc.issue.application.idempotency.IdempotencyKey;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * A summary to publish as received: the text is unchecked, validated by the
 * domain.
 *
 * @param expectedVersion the issue version the administrator was looking at ({@code If-Match})
 */
public record PublishSummaryCommand(
        UserId actor,
        IdempotencyKey idempotencyKey,
        IssueId issueId,
        long expectedVersion,
        String summary,
        String correlationId) {

    public PublishSummaryCommand {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    @Override
    public String toString() {
        return "PublishSummaryCommand[issueId=" + issueId + ", expectedVersion=" + expectedVersion + "]";
    }
}
