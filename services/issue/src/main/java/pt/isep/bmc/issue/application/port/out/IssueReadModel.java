package pt.isep.bmc.issue.application.port.out;

import java.util.List;
import java.util.Optional;
import pt.isep.bmc.issue.application.query.IssueHistoryItem;
import pt.isep.bmc.issue.application.query.IssueListCriteria;
import pt.isep.bmc.issue.application.query.IssuePage;
import pt.isep.bmc.issue.application.query.IssueView;
import pt.isep.bmc.issue.domain.issue.IssueId;

/**
 * Query side for issues: projections over the issue, its area and building,
 * its current triage decision and its history. The query service decides
 * what a caller may see; this port only reads.
 */
public interface IssueReadModel {

    /** The issue with its current decision, reason included. */
    Optional<IssueView> findView(IssueId id);

    /** Issues matching the criteria, newest first, without triage reasons. */
    IssuePage list(IssueListCriteria criteria);

    /** The issue's history, oldest first, reasons included. */
    List<IssueHistoryItem> findHistory(IssueId id);
}
