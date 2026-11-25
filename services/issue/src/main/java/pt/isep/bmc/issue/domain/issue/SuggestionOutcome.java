package pt.isep.bmc.issue.domain.issue;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.issue.domain.event.DomainEvent;

/**
 * Everything one recorded suggestion produces, to be stored in one
 * transaction.
 *
 * @param issue  the next version of the issue, or empty when the suggestion
 *               was late and the issue stays as it is
 * @param events for other services; none when the issue did not change
 */
public record SuggestionOutcome(
        Optional<Issue> issue, TriageSuggestion suggestion, IssueHistoryEntry history, List<DomainEvent> events) {

    public SuggestionOutcome {
        Objects.requireNonNull(issue, "issue");
        Objects.requireNonNull(suggestion, "suggestion");
        Objects.requireNonNull(history, "history");
        events = List.copyOf(events);
    }
}
