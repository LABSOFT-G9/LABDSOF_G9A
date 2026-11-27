package pt.isep.bmc.dispatch.application.port.in;

import java.util.List;
import pt.isep.bmc.dispatch.application.query.InterventionView;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * Interventions for administrators of their building (US-06): one by ID, so a
 * refused change can be reloaded, and the list of an issue's interventions.
 * The assigned contractor's view comes with US-07.
 */
public interface GetInterventionQuery {

    InterventionView get(UserId actor, InterventionId id);

    List<InterventionView> listForIssue(UserId actor, IssueId issueId);
}
