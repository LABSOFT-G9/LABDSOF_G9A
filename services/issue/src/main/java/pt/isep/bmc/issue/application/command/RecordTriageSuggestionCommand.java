package pt.isep.bmc.issue.application.command;

import java.util.Objects;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.SuggestionInput;

/**
 * @param correlationId the correlation ID of the event that carried the
 *                      suggestion, passed on to the events it causes
 */
public record RecordTriageSuggestionCommand(IssueId issueId, SuggestionInput suggestion, String correlationId) {

    public RecordTriageSuggestionCommand {
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(suggestion, "suggestion");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
