package pt.isep.bmc.issue.domain.issue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a run of the critical check adds to an issue, to be stored with it in
 * one transaction: one signal and one internal history entry per rule that
 * matched and was not raised before.
 *
 * @param failure the class of the exception that stopped the check, to be logged; empty when it ran
 */
public record CriticalOutcome(List<CriticalSignal> signals, List<IssueHistoryEntry> history, Optional<String> failure) {

    public CriticalOutcome {
        signals = List.copyOf(signals);
        history = List.copyOf(history);
        Objects.requireNonNull(failure, "failure");
    }

    /**
     * @param issue         the issue once the check was recorded on it
     * @param firstSequence the sequence of the issue's next history entry
     * @param alreadyRaised codes the issue already has; they are kept as they are and not raised twice
     */
    public static CriticalOutcome of(
            Issue issue, CriticalFindings findings, int firstSequence, Set<String> alreadyRaised, Instant now) {
        Objects.requireNonNull(issue, "issue");
        Objects.requireNonNull(alreadyRaised, "alreadyRaised");
        Objects.requireNonNull(now, "now");
        List<CriticalSignal> signals = new ArrayList<>();
        List<IssueHistoryEntry> history = new ArrayList<>();
        if (findings.completed()) {
            String version = findings.rulesVersion().orElseThrow();
            int sequence = firstSequence;
            for (String code : findings.codes()) {
                if (alreadyRaised.contains(code)) {
                    continue;
                }
                signals.add(new CriticalSignal(issue.id(), code, version, sequence, now));
                history.add(IssueHistoryEntry.criticalAlertRaised(issue, sequence, now));
                sequence++;
            }
        }
        return new CriticalOutcome(signals, history, findings.failure());
    }
}
