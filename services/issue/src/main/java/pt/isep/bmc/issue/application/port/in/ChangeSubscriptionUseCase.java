package pt.isep.bmc.issue.application.port.in;

import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * A user turns their own notifications for an issue on or off (US-04).
 * Separate from impact, off by default, safe to repeat. Turning it off never
 * removes access to the issue.
 */
public interface ChangeSubscriptionUseCase {

    /** @return whether the caller is subscribed after the change: always {@code true} */
    boolean subscribe(UserId actor, IssueId issueId);

    /** @return whether the caller is subscribed after the change: always {@code false} */
    boolean unsubscribe(UserId actor, IssueId issueId);
}
