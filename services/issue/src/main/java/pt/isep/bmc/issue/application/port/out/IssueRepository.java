package pt.isep.bmc.issue.application.port.out;

import java.util.Optional;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;
import pt.isep.bmc.issue.domain.issue.IssueId;

public interface IssueRepository {

    void add(Issue issue);

    /**
     * Loads the issue and locks it until the transaction ends, so two
     * commands on the same issue run one after the other: the second one
     * sees the first one's version and history.
     */
    Optional<Issue> findForUpdate(IssueId id);

    /** Stores the next version of an issue loaded with {@link #findForUpdate}. */
    void update(Issue issue);

    void appendHistory(IssueHistoryEntry entry);

    /** The sequence number for the issue's next history entry; call it while holding the issue's lock. */
    int nextHistorySequence(IssueId id);
}
