package pt.isep.bmc.issue.application.query;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.application.port.in.GetIssueHistoryQuery;
import pt.isep.bmc.issue.application.port.in.GetIssueQuery;
import pt.isep.bmc.issue.application.port.in.ListIssuesQuery;
import pt.isep.bmc.issue.application.port.out.IssueReadModel;
import pt.isep.bmc.issue.application.port.out.MembershipRepository;
import pt.isep.bmc.issue.application.port.out.ReportRepository;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.HistoryVisibility;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.membership.BuildingAccess;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * Issue queries, projected for the caller's role (access matrix, security
 * and privacy assessment section 2). Every read checks memberships on the
 * server; anything outside the caller's scope is reported as not found.
 *
 * <ul>
 *   <li>Administrators of the building: everything, including internal
 *       history, triage reasons and the unconfirmed suggestion.</li>
 *   <li>Whoever reported the issue and, once a summary is published, every
 *       member of the building: its status, the decided category and urgency,
 *       the summary, the impact count and public history. Never a reason, a
 *       suggestion, an internal entry or anyone's report text.</li>
 *   <li>Everyone else, including contractors and other buildings'
 *       administrators: not found.</li>
 * </ul>
 */
public final class IssueQueryService implements GetIssueQuery, ListIssuesQuery, GetIssueHistoryQuery {

    private final TransactionRunner transactions;
    private final IssueReadModel readModel;
    private final MembershipRepository memberships;
    private final ReportRepository reports;
    private final Clock clock;

    public IssueQueryService(
            TransactionRunner transactions,
            IssueReadModel readModel,
            MembershipRepository memberships,
            ReportRepository reports,
            Clock clock) {
        this.transactions = transactions;
        this.readModel = readModel;
        this.memberships = memberships;
        this.reports = reports;
        this.clock = clock;
    }

    @Override
    public IssueView get(UserId actor, IssueId issueId) {
        return transactions.readOnly(() -> {
            IssueView issue = visible(actor, issueId);
            return administers(actor, issue.buildingId()) ? issue : issue.withoutTriageReason().withoutSuggestion();
        });
    }

    @Override
    public IssuePage list(UserId actor, Optional<BuildingId> building, int page, int size) {
        return transactions.readOnly(() -> {
            Instant now = clock.instant();
            BuildingAccess access = BuildingAccess.of(actor, memberships.findByUser(actor), now);
            IssuePage found = readModel.list(new IssueListCriteria(
                    actor, access.administeredBuildings(), access.memberBuildings(), building, page, size, now));
            // One page mixes the buildings the caller administers with issues the caller only follows.
            List<IssueView> items = found.items().stream()
                    .map(issue -> access.administers(issue.buildingId()) ? issue : issue.withoutSuggestion())
                    .toList();
            return new IssuePage(items, found.page(), found.size(), found.hasMore());
        });
    }

    @Override
    public List<IssueHistoryItem> history(UserId actor, IssueId issueId) {
        return transactions.readOnly(() -> {
            IssueView issue = visible(actor, issueId);
            List<IssueHistoryItem> entries = readModel.findHistory(issueId);
            if (administers(actor, issue.buildingId())) {
                return entries;
            }
            return entries.stream()
                    .filter(entry -> entry.visibility() == HistoryVisibility.PUBLIC)
                    .map(IssueHistoryItem::withoutReason)
                    .toList();
        });
    }

    /** The issue, or not found when it does not exist or the caller may not see it. */
    private IssueView visible(UserId actor, IssueId issueId) {
        IssueView issue = readModel.findView(issueId, actor, clock.instant()).orElseThrow(NotFoundException::new);
        if (!access(actor).canViewIssue(issue.buildingId(), reports.existsByIssueAndAuthor(issueId, actor),
                issue.summary().isPresent())) {
            throw new NotFoundException();
        }
        return issue;
    }

    private boolean administers(UserId actor, BuildingId building) {
        return access(actor).administers(building);
    }

    private BuildingAccess access(UserId actor) {
        return BuildingAccess.of(actor, memberships.findByUser(actor), clock.instant());
    }
}
