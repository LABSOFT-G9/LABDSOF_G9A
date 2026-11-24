package pt.isep.bmc.issue.application.port.out;

import java.time.Instant;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * Impact confirmations: at most one per user and issue (technical design
 * 2.1). Call these while holding the issue's lock, so a repeated or concurrent
 * confirmation never counts twice.
 */
public interface ImpactRepository {

    boolean exists(IssueId issueId, UserId userId);

    void add(IssueId issueId, UserId userId, Instant confirmedAt);

    /** Withdrawing deletes the confirmation. */
    void remove(IssueId issueId, UserId userId);

    long count(IssueId issueId);
}
