package pt.isep.bmc.issue.domain.issue;

import java.time.Instant;
import java.util.Objects;

/**
 * One rule of the critical check that matched a report of the issue
 * (technical design 2.1, {@code criticalSignals}). A fact about the report:
 * nothing removes it, not a suggestion, a retry or a decision (US-14).
 *
 * <p>It holds the rule's code, never the words that matched: the report text
 * stays private.
 *
 * @param historySequence the {@code critical_alert_raised} entry of the issue's history that records it
 */
public record CriticalSignal(IssueId issueId, String code, String rulesVersion, int historySequence, Instant raisedAt) {

    public CriticalSignal {
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(rulesVersion, "rulesVersion");
        Objects.requireNonNull(raisedAt, "raisedAt");
        if (!CriticalFindings.CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("not a rule code");
        }
        if (historySequence < 1) {
            throw new IllegalArgumentException("historySequence starts at 1");
        }
    }
}
