package pt.isep.bmc.issue.application.port.out;

import java.util.List;
import pt.isep.bmc.issue.domain.dispatch.InterventionProjection;
import pt.isep.bmc.issue.domain.issue.IssueId;

/** Issue's copies of Dispatch's interventions (US-07-03). Read and written under the issue's lock. */
public interface InterventionRepository {

    /** Every intervention Issue knows for the issue, oldest first. */
    List<InterventionProjection> forIssue(IssueId issueId);

    /** Stores a new intervention, or the new state and version of a known one. */
    void save(InterventionProjection intervention);
}
