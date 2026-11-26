package pt.isep.bmc.issue.application.command;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import pt.isep.bmc.issue.application.port.in.CompletePendingCriticalChecksUseCase;
import pt.isep.bmc.issue.application.port.out.CriticalCheckLog;
import pt.isep.bmc.issue.application.port.out.CriticalSignalRepository;
import pt.isep.bmc.issue.application.port.out.IssueRepository;
import pt.isep.bmc.issue.application.port.out.ReportRepository;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;
import pt.isep.bmc.issue.domain.issue.CriticalCheckStatus;
import pt.isep.bmc.issue.domain.issue.CriticalFindings;
import pt.isep.bmc.issue.domain.issue.CriticalOutcome;
import pt.isep.bmc.issue.domain.issue.CriticalRules;
import pt.isep.bmc.issue.domain.issue.CriticalSignal;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.report.Report;

/**
 * Completes critical checks left pending (technical design 5, failure table:
 * "Issue critical detector fails"). One transaction per issue, holding the
 * issue's lock, so a decision or a suggestion on the same issue runs before
 * or after it, never in between.
 *
 * <p>Signals already on the issue are kept and never raised twice. An issue
 * whose check fails again, or cannot be stored, stays pending, is logged and
 * is not tried again in the same run, so a run always ends and one issue
 * never stops the others.
 */
public final class CompletePendingCriticalChecksService implements CompletePendingCriticalChecksUseCase {

    public static final int DEFAULT_BATCH_SIZE = 100;

    private final TransactionRunner transactions;
    private final IssueRepository issues;
    private final ReportRepository reports;
    private final CriticalSignalRepository signals;
    private final CriticalRules rules;
    private final CriticalCheckLog log;
    private final Clock clock;
    private final int batchSize;

    public CompletePendingCriticalChecksService(
            TransactionRunner transactions,
            IssueRepository issues,
            ReportRepository reports,
            CriticalSignalRepository signals,
            CriticalRules rules,
            CriticalCheckLog log,
            Clock clock,
            int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.transactions = transactions;
        this.issues = issues;
        this.reports = reports;
        this.signals = signals;
        this.rules = rules;
        this.log = log;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Override
    public Result completePending() {
        int completed = 0;
        int stillPending = 0;
        int alertsRaised = 0;
        Optional<IssueId> after = Optional.empty();
        while (true) {
            Optional<IssueId> from = after;
            List<IssueId> batch = transactions.readOnly(() -> issues.findWithPendingCriticalCheck(from, batchSize));
            for (IssueId id : batch) {
                Optional<Integer> raised;
                try {
                    raised = transactions.inTransaction(() -> complete(id));
                } catch (RuntimeException e) {
                    // Storing this issue's check failed and was rolled back: the others still get theirs.
                    log.checkFailed(id, e.getClass().getName());
                    raised = Optional.empty();
                }
                if (raised.isPresent()) {
                    completed++;
                    alertsRaised += raised.get();
                } else {
                    stillPending++;
                }
            }
            if (batch.size() < batchSize) {
                return new Result(completed, stillPending, alertsRaised);
            }
            after = Optional.of(batch.getLast());
        }
    }

    /** @return how many signals were raised, or empty when the check is still pending */
    private Optional<Integer> complete(IssueId id) {
        Issue issue = issues.findForUpdate(id).orElseThrow();
        if (issue.criticalCheckStatus() == CriticalCheckStatus.COMPLETED) {
            // Another instance completed it between the listing and the lock.
            return Optional.of(0);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Set<String> alreadyRaised = signals.findCodes(id);
        List<Report> ofIssue = reports.findByIssue(id);
        CriticalFindings check = ofIssue.isEmpty()
                ? CriticalFindings.notChecked(IllegalStateException.class.getName())
                : CriticalFindings.none(rules.version());
        for (Report report : ofIssue) {
            check = check.and(CriticalFindings.of(rules, report.text()));
        }
        if (!check.completed()) {
            log.checkFailed(id, check.failure().orElseThrow());
            return Optional.empty();
        }
        boolean raisesAlert = check.codes().stream().anyMatch(code -> !alreadyRaised.contains(code));
        Issue next = issue.completeCriticalCheck(check, raisesAlert, now).orElseThrow();
        CriticalOutcome outcome = CriticalOutcome.of(next, check, issues.nextHistorySequence(id), alreadyRaised, now);
        issues.update(next);
        for (CriticalSignal signal : outcome.signals()) {
            signals.add(signal);
        }
        for (IssueHistoryEntry entry : outcome.history()) {
            issues.appendHistory(entry);
        }
        return Optional.of(outcome.signals().size());
    }
}
