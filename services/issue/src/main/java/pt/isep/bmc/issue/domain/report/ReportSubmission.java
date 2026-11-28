package pt.isep.bmc.issue.domain.report;

import java.util.List;
import java.util.Objects;
import pt.isep.bmc.issue.domain.event.DomainEvent;
import pt.isep.bmc.issue.domain.issue.CriticalOutcome;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;

/**
 * Everything one accepted report produces, to be stored in one transaction
 * (technical design 3.1 and 5): the new issue in {@code received}, the report,
 * the first public history entry, what the critical check raised, and the
 * {@code ReportSubmitted} and {@code TriageRequested} events for the outbox.
 *
 * @param critical the signals of the critical check and their history entries, which follow {@code history}
 */
public record ReportSubmission(
        Issue issue, Report report, IssueHistoryEntry history, CriticalOutcome critical, List<DomainEvent> events) {

    public ReportSubmission {
        Objects.requireNonNull(issue, "issue");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(critical, "critical");
        events = List.copyOf(events);
    }
}
