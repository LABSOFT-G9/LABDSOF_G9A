package pt.isep.bmc.dispatch.application.command;

import java.util.Objects;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/**
 * Issue closed an issue: {@code IssueResolved} (validated or manual) or
 * {@code IssueCancelled}. The event's {@code activeInterventionId} is not
 * needed: every non-terminal intervention of the issue is ended.
 */
public record IssueClosure(IssueId issueId, Kind kind, String correlationId) {

    public enum Kind {
        RESOLVED,
        CANCELLED
    }

    public IssueClosure {
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
