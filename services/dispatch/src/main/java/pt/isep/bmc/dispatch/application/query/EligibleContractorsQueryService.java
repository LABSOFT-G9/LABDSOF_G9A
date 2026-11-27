package pt.isep.bmc.dispatch.application.query;

import pt.isep.bmc.dispatch.application.port.in.ListEligibleContractorsQuery;
import pt.isep.bmc.dispatch.application.port.out.ContractorCatalogue;
import pt.isep.bmc.dispatch.application.port.out.IssueProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.MembershipProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.TransactionRunner;
import pt.isep.bmc.dispatch.domain.contractor.ContractorEligibility;
import pt.isep.bmc.dispatch.domain.projection.AdministratorAccess;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * Eligible contractors for an issue (US-06), for administrators of its
 * building only: anyone else, and an issue unknown to the projection, get
 * {@code 404}. The list is answered also when the issue is not dispatchable,
 * with {@code dispatchable} saying so, so the administrator sees why no
 * draft can be saved yet.
 */
public final class EligibleContractorsQueryService implements ListEligibleContractorsQuery {

    private final TransactionRunner transactions;
    private final IssueProjectionRepository issues;
    private final MembershipProjectionRepository memberships;
    private final ContractorCatalogue catalogue;

    public EligibleContractorsQueryService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            MembershipProjectionRepository memberships,
            ContractorCatalogue catalogue) {
        this.transactions = transactions;
        this.issues = issues;
        this.memberships = memberships;
        this.catalogue = catalogue;
    }

    @Override
    public EligibleContractors list(UserId actor, IssueId issueId) {
        return transactions.readOnly(() -> {
            ProjectedIssue issue = issues.find(issueId).orElseThrow(AccessDeniedException::new);
            AdministratorAccess.of(actor, memberships.findByUser(actor)).requireAdministers(issue.buildingId());
            return new EligibleContractors(issue.issueId(), issue.state(), issue.dispatchable(), issue.category(),
                    ContractorEligibility.rank(catalogue.findCovering(issue.buildingId()), issue));
        });
    }
}
