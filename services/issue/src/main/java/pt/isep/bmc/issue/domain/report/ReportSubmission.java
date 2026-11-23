package pt.isep.bmc.issue.domain.report;

import java.util.List;
import java.util.Objects;
import pt.isep.bmc.issue.domain.event.DomainEvent;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;

/**
 * Everything one accepted report produces, to be stored in one transaction
 * (technical design 3.1 and 5): the new issue in {@code received}, the report,
 * the first public history entry and the {@code ReportSubmitted} and
 * {@code TriageRequested} events for the outbox.
 */
public record ReportSubmission(Issue issue, Report report, IssueHistoryEntry history, List<DomainEvent> events) {

    public ReportSubmission {
        Objects.requireNonNull(issue, "issue");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(history, "history");
        events = List.copyOf(events);
    }
}
