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
 * @param recorded     what is kept of the event; empty when it must wait for an earlier one and be delivered again
 * @param events       {@code IssueStateChanged} when the issue state moved, for Dispatch's projection
 */
public record DispatchOutcome(
        Optional<Issue> issue,
        Optional<InterventionProjection> intervention,
        Optional<IssueHistoryEntry> history,
        Optional<DispatchEventRecord> recorded,
        List<DomainEvent> events) {

    public DispatchOutcome {
        Objects.requireNonNull(issue, "issue");
        Objects.requireNonNull(intervention, "intervention");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(recorded, "recorded");
        events = List.copyOf(events);
        if (recorded.isEmpty() && (issue.isPresent() || intervention.isPresent() || history.isPresent()
                || !events.isEmpty())) {
            throw new IllegalArgumentException("an event delivered again later writes nothing now");
        }
    }

    /**
     * An event of an intervention Issue cannot place yet: the event that ended the active one is still on its way
     * (Dispatch keeps at most one active intervention per issue). Nothing is written, so a later delivery finds it new.
     */
    public static DispatchOutcome retryLater() {
        return new DispatchOutcome(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }

    /** Whether the event must be delivered again later. */
    public boolean retryLaterNeeded() {
        return recorded.isEmpty();
    }

    /** What is kept of the event. */
    public DispatchEventRecord record() {
        return recorded.orElseThrow(() -> new IllegalStateException("an event delivered again later is not kept"));
    }
}
