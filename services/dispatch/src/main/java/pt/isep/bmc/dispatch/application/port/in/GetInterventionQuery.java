package pt.isep.bmc.dispatch.application.port.in;

import java.util.List;
import pt.isep.bmc.dispatch.application.query.AssignmentView;
import pt.isep.bmc.dispatch.application.query.InterventionRead;
import pt.isep.bmc.dispatch.application.query.InterventionView;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * Interventions for administrators of their building (US-06) and for the
 * assigned contractor (US-07). Anyone else gets {@code 404}.
 */
public interface GetInterventionQuery {

    /** The administrator's view, or the contractor's once the request was sent to them. */
    InterventionRead get(UserId actor, InterventionId id);

    List<InterventionView> listForIssue(UserId actor, IssueId issueId);

    /** {@code GET /interventions?assigned=me}: the requests sent to the caller as a contractor. */
    List<AssignmentView> listAssigned(UserId actor);
}
