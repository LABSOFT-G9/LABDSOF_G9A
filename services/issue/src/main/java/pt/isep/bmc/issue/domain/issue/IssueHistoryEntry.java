package pt.isep.bmc.issue.domain.issue;

import java.time.Instant;
import java.util.Objects;
import pt.isep.bmc.issue.domain.report.ReportSource;

/**
 * One entry of an issue's history (technical design 2.1). Holds the actor's
 * role, never their identity or any free text: a decision's reason and its
 * author are in the {@link TriageDecision} of the same issue version.
 *
 * @param issueVersion the issue's version once the entry's change was applied
 * @param actorRole    {@code resident}, {@code administrator}, {@code contractor} or {@code system}
 */
public record IssueHistoryEntry(
        IssueId issueId,
        int sequence,
        HistoryEntryType type,
        HistoryVisibility visibility,
        String actorRole,
        long issueVersion,
        Instant occurredAt) {

    public static final String ROLE_RESIDENT = "resident";
    public static final String ROLE_ADMINISTRATOR = "administrator";
    public static final String ROLE_SYSTEM = "system";

    public IssueHistoryEntry {
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(actorRole, "actorRole");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence starts at 1");
        }
        if (issueVersion < Issue.INITIAL_VERSION) {
            throw new IllegalArgumentException("issueVersion starts at " + Issue.INITIAL_VERSION);
        }
    }

    /** First, public entry of a new issue. */
    public static IssueHistoryEntry reportReceived(IssueId issueId, ReportSource source, Instant at) {
        String role = source == ReportSource.RESIDENT ? ROLE_RESIDENT : ROLE_ADMINISTRATOR;
        return new IssueHistoryEntry(issueId, 1, HistoryEntryType.REPORT_RECEIVED, HistoryVisibility.PUBLIC, role,
                Issue.INITIAL_VERSION, at);
    }

    /**
     * An administrator decided category and urgency. Public: residents who
     * may follow the issue see that it was triaged, and as what, never why.
     */
    public static IssueHistoryEntry triageDecided(Issue triaged, int sequence) {
        return new IssueHistoryEntry(triaged.id(), sequence, HistoryEntryType.TRIAGE_DECIDED, HistoryVisibility.PUBLIC,
                ROLE_ADMINISTRATOR, triaged.version(), triaged.updatedAt());
    }

    /**
     * An administrator published or replaced the shared summary. Public: it
     * is the moment the residents of the building can see the issue.
     */
    public static IssueHistoryEntry summaryPublished(Issue published, int sequence) {
        return new IssueHistoryEntry(published.id(), sequence, HistoryEntryType.SUMMARY_PUBLISHED,
                HistoryVisibility.PUBLIC, ROLE_ADMINISTRATOR, published.version(), published.updatedAt());
    }

    /**
     * A rule of the critical check matched. Internal: only the building's
     * administrators see alerts. Which rule is in the {@link CriticalSignal}
     * with the same sequence.
     *
     * @param issue the issue once the check was recorded
     */
    public static IssueHistoryEntry criticalAlertRaised(Issue issue, int sequence, Instant at) {
        return new IssueHistoryEntry(issue.id(), sequence, HistoryEntryType.CRITICAL_ALERT_RAISED,
                HistoryVisibility.INTERNAL, ROLE_SYSTEM, issue.version(), at);
    }

    /**
     * A triage suggestion arrived. Internal. A suggestion that was applied
     * moved the issue to {@code in_triage}; a late one changed nothing, and its
     * entry keeps the version the issue already had.
     *
     * @param issue the issue once the suggestion was recorded
     */
    public static IssueHistoryEntry triageSuggested(Issue issue, int sequence, Instant at) {
        return new IssueHistoryEntry(issue.id(), sequence, HistoryEntryType.TRIAGE_SUGGESTED,
                HistoryVisibility.INTERNAL, ROLE_SYSTEM, issue.version(), at);
    }
}
