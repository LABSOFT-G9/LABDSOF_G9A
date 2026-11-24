package pt.isep.bmc.issue.application.port.in;

import pt.isep.bmc.issue.application.query.IssueView;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.membership.UserId;

/** {@code GET /issues/{id}} (technical design 4.2). */
public interface GetIssueQuery {

    /** The issue as the caller may see it; not found when it is outside the caller's scope. */
    IssueView get(UserId actor, IssueId issueId);
}
