package pt.isep.bmc.dispatch.application.port.in;

import pt.isep.bmc.dispatch.application.command.IssueClosure;

/**
 * Applies Issue's {@code IssueResolved} or {@code IssueCancelled} (US-09,
 * technical design 4.3 step 8): every non-terminal intervention of the issue
 * is closed or cancelled. Called by the event consumer; until it is wired
 * (EN-02-02), by tests with synthetic events.
 */
public interface ApplyIssueClosureUseCase {

    /** @return how many interventions changed; a repeated event changes none */
    int apply(IssueClosure closure);
}
