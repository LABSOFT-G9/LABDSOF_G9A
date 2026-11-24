package pt.isep.bmc.issue.application.port.in;

import java.util.List;
import pt.isep.bmc.issue.application.query.IssueHistoryItem;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.membership.UserId;

/** {@code GET /issues/{id}/history} (technical design 4.2). */
public interface GetIssueHistoryQuery {

    /**
     * The issue's history, oldest first. Administrators of the building get
     * every entry; anyone else who may see the issue gets the public entries,
     * without reasons.
     */
    List<IssueHistoryItem> history(UserId actor, IssueId issueId);
}
