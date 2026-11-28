package pt.isep.bmc.issue.application.port.out;

import pt.isep.bmc.issue.domain.issue.IssueId;

/**
 * Where a failed critical check is made visible to operators (US-14:
 * processing failures are visible in logs, without private data).
 */
public interface CriticalCheckLog {

    /**
     * The rule set did not run for this issue, whose check stays pending.
     *
     * @param failure the class of the exception; never its message, which could quote a report
     */
    void checkFailed(IssueId issueId, String failure);
}
