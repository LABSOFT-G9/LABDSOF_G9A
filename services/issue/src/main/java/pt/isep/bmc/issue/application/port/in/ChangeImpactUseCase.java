package pt.isep.bmc.issue.application.port.in;

import pt.isep.bmc.issue.application.query.ImpactView;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * A resident confirms or withdraws their own "this affects me too" (US-04).
 * Both are safe to repeat. Neither changes the issue, its version, its
 * category or its urgency.
 */
public interface ChangeImpactUseCase {

    /** @return the count and the caller's own confirmation after the change */
    ImpactView confirm(UserId actor, IssueId issueId);

    ImpactView withdraw(UserId actor, IssueId issueId);
}
