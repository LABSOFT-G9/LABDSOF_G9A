package pt.isep.bmc.dispatch.application.query;

import java.util.List;
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
 * step 5). The building is the intervention's own snapshot, checked against
 * the membership projection on every read, so a revoked administrator loses
 * access as soon as {@code MembershipRevoked} is applied.
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
    public InterventionView get(UserId actor, InterventionId id) {
        return transactions.readOnly(() -> {
            InterventionView view = readModel.find(id).orElseThrow(AccessDeniedException::new);
            access(actor).requireAdministers(view.buildingId());
            return view;
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

    private AdministratorAccess access(UserId actor) {
        return AdministratorAccess.of(actor, memberships.findByUser(actor));
    }
}
