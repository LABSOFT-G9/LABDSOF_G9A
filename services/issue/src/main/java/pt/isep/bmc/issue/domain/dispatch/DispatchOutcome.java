package pt.isep.bmc.issue.domain.dispatch;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.issue.domain.event.DomainEvent;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;

/**
 * Everything one Dispatch event writes, in one transaction.
 *
 * @param issue        the issue in its new state, when the event moved it
 * @param intervention the intervention's projection to store, when the event is newer
 * @param history      the entry that shows the event, unless it failed
 * @param events       {@code IssueStateChanged} when the issue state moved, for Dispatch's projection
 */
public record DispatchOutcome(
        Optional<Issue> issue,
        Optional<InterventionProjection> intervention,
        Optional<IssueHistoryEntry> history,
        DispatchEventRecord record,
        List<DomainEvent> events) {

    public DispatchOutcome {
        Objects.requireNonNull(issue, "issue");
        Objects.requireNonNull(intervention, "intervention");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(record, "record");
        events = List.copyOf(events);
    }
}
