package pt.isep.bmc.issue.domain.issue;

import java.time.Instant;
import java.util.Objects;
import pt.isep.bmc.issue.domain.report.ReportSource;

/**
 * One entry of an issue's history (technical design 2.1). Holds the actor's
 * role, never their identity or any free text.
 *
 * @param actorRole {@code resident} or {@code administrator}
 */
public record IssueHistoryEntry(
        IssueId issueId,
        int sequence,
        HistoryEntryType type,
        HistoryVisibility visibility,
        String actorRole,
        Instant occurredAt) {

    public IssueHistoryEntry {
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(actorRole, "actorRole");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence starts at 1");
        }
    }

    /** First, public entry of a new issue. */
    public static IssueHistoryEntry reportReceived(IssueId issueId, ReportSource source, Instant at) {
        String role = source == ReportSource.RESIDENT ? "resident" : "administrator";
        return new IssueHistoryEntry(issueId, 1, HistoryEntryType.REPORT_RECEIVED, HistoryVisibility.PUBLIC, role, at);
    }
}
