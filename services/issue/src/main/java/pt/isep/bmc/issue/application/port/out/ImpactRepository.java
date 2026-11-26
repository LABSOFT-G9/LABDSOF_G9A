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

    /**
     * How many residents the issue affects: the confirmations whose author is
     * an active resident of the issue's building at {@code now}. A
     * confirmation stays stored when its author leaves the building, but it
     * is not counted until the membership is active again.
     */
    long count(IssueId issueId, Instant now);
}
