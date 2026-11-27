package pt.isep.bmc.dispatch.application.port.out;

import java.util.Optional;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/**
 * Dispatch's copy of Issue's issues (technical design 2.2 and 4.3). Written
 * only by the {@code IssueStateChanged} consumer, never by a request.
 */
public interface IssueProjectionRepository {

    Optional<ProjectedIssue> find(IssueId id);

    /**
     * Locks the row until the transaction ends. Creating an intervention and
     * applying an Issue event lock the same row, so they are serialised
     * (technical design 4.3 step 8).
     */
    Optional<ProjectedIssue> findForUpdate(IssueId id);

    void insert(ProjectedIssue issue);

    void update(ProjectedIssue issue);
}
