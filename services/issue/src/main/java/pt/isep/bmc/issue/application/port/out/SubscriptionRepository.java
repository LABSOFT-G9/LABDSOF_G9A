package pt.isep.bmc.issue.application.port.out;

import java.time.Instant;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * A user's own choice to be notified about an issue (US-04): no row means not
 * subscribed. Call these while holding the issue's lock.
 */
public interface SubscriptionRepository {

    boolean exists(IssueId issueId, UserId userId);

    void add(IssueId issueId, UserId userId, Instant subscribedAt);

    void remove(IssueId issueId, UserId userId);
}
