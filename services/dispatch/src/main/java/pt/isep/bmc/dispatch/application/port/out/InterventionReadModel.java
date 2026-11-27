package pt.isep.bmc.dispatch.application.port.out;

import java.util.List;
import java.util.Optional;
import pt.isep.bmc.dispatch.application.query.InterventionView;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/** Query side for interventions: the intervention, its contractor and its current proposal. */
public interface InterventionReadModel {

    Optional<InterventionView> find(InterventionId id);

    /** The issue's interventions, newest first, terminal ones included. */
    List<InterventionView> findByIssue(IssueId issueId);
}
