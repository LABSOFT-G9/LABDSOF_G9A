package pt.isep.bmc.issue.domain.issue;

import java.util.List;
import pt.isep.bmc.issue.domain.event.DomainEvent;

/**
 * Everything one publication produces, to be stored in one transaction: the
 * next version of the issue, the summary, its history entry and the events for
 * other services.
 */
public record PublicationOutcome(Issue issue, IssueSummary summary, IssueHistoryEntry history, List<DomainEvent> events) {

    public PublicationOutcome {
        events = List.copyOf(events);
    }
}
