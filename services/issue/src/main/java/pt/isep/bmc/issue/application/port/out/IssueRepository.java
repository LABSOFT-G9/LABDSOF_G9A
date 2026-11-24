package pt.isep.bmc.issue.application.port.out;

import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;

public interface IssueRepository {

    void add(Issue issue);

    void appendHistory(IssueHistoryEntry entry);
}
