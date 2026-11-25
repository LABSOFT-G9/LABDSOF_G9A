package pt.isep.bmc.issue.domain.issue;

import java.util.List;
import pt.isep.bmc.issue.domain.event.DomainEvent;

/**
 * Everything one triage decision produces, to be stored in one transaction:
 * the next version of the issue, the decision, its history entry and the
 * events for other services.
 */
public record TriageOutcome(Issue issue, TriageDecision decision, IssueHistoryEntry history, List<DomainEvent> events) {

    public TriageOutcome {
        events = List.copyOf(events);
    }
}
