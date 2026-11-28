package pt.isep.bmc.dispatch.application.query;

import java.util.List;
import java.util.Map;
import pt.isep.bmc.dispatch.application.port.in.GetInterventionQuery;
import pt.isep.bmc.dispatch.application.port.out.InterventionReadModel;
import pt.isep.bmc.dispatch.application.port.out.IssueProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.MembershipProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.TransactionRunner;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.projection.AdministratorAccess;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * Interventions for administrators of their building (technical design 4.3
 * step 5) and for the assigned contractor (US-07). The building is the
 * intervention's own snapshot, checked against the membership projection on
 * every read, so a revoked administrator loses access as soon as {@code
 * MembershipRevoked} is applied. The contractor is recognised by the
 * catalogue entry's login, and only once the request was sent.
 */
public final class InterventionQueryService implements GetInterventionQuery {

    private final TransactionRunner transactions;
    private final InterventionReadModel readModel;
    private final IssueProjectionRepository issues;
    private final MembershipProjectionRepository memberships;

    public InterventionQueryService(
            TransactionRunner transactions,
            InterventionReadModel readModel,
            IssueProjectionRepository issues,
            MembershipProjectionRepository memberships) {
        this.transactions = transactions;
        this.readModel = readModel;
        this.issues = issues;
        this.memberships = memberships;
    }

    @Override
    public InterventionRead get(UserId actor, InterventionId id) {
        return transactions.readOnly(() -> {
            InterventionView view = readModel.find(id).orElseThrow(AccessDeniedException::new);
            if (access(actor).administers(view.buildingId())) {
                return view;
            }
            if (sentTo(view, actor)) {
                return assignment(view, issues.findAll(List.of(view.issueId())));
            }
            throw new AccessDeniedException();
        });
    }

    @Override
    public List<InterventionView> listForIssue(UserId actor, IssueId issueId) {
        return transactions.readOnly(() -> {
            ProjectedIssue issue = issues.find(issueId).orElseThrow(AccessDeniedException::new);
            access(actor).requireAdministers(issue.buildingId());
            return readModel.findByIssue(issueId);
        });
    }

    @Override
    public List<AssignmentView> listAssigned(UserId actor) {
        return transactions.readOnly(() -> {
            List<InterventionView> sent = readModel.findSentTo(actor).stream().filter(view -> sentTo(view, actor)).toList();
            Map<IssueId, ProjectedIssue> projected = issues.findAll(sent.stream().map(InterventionView::issueId).toList());
            return sent.stream().map(view -> assignment(view, projected)).toList();
        });
    }

    private static boolean sentTo(InterventionView view, UserId actor) {
        return view.contractorContacted() && view.contractor().userId().filter(actor::equals).isPresent();
    }

    /** @param projected the issues of the interventions listed, read in one go */
    private static AssignmentView assignment(InterventionView view, Map<IssueId, ProjectedIssue> projected) {
        ProjectedIssue issue = projected.get(view.issueId());
        if (issue == null) {
            throw new IllegalStateException("intervention points at an issue missing from the projection");
        }
        return AssignmentView.of(view, new AssignmentView.Work(issue.category(), issue.urgency(), issue.areaLabel()));
    }

    private AdministratorAccess access(UserId actor) {
        return AdministratorAccess.of(actor, memberships.findByUser(actor));
    }
}
