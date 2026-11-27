package pt.isep.bmc.issue.application.command;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.application.port.in.ChangeImpactUseCase;
import pt.isep.bmc.issue.application.port.out.ImpactRepository;
import pt.isep.bmc.issue.application.port.out.IssueRepository;
import pt.isep.bmc.issue.application.port.out.MembershipRepository;
import pt.isep.bmc.issue.application.port.out.ReportRepository;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;
import pt.isep.bmc.issue.application.query.ImpactView;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueSharing;
import pt.isep.bmc.issue.domain.membership.BuildingAccess;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * Confirms or withdraws the caller's own impact on a shared issue (US-04).
 *
 * <p>The issue row is locked for the check and the write, so the same user
 * confirming twice at once still leaves one confirmation, and the returned
 * count includes every confirmation committed before, of people who are
 * active residents of the building now. The issue itself is
 * never written: its version, category and urgency stay as they are, and no
 * event or history entry is produced (the count says nothing about who).
 */
public final class ImpactService implements ChangeImpactUseCase {

    private final TransactionRunner transactions;
    private final MembershipRepository memberships;
    private final IssueRepository issues;
    private final ReportRepository reports;
    private final ImpactRepository impacts;
    private final Clock clock;

    public ImpactService(
            TransactionRunner transactions,
            MembershipRepository memberships,
            IssueRepository issues,
            ReportRepository reports,
            ImpactRepository impacts,
            Clock clock) {
        this.transactions = transactions;
        this.memberships = memberships;
        this.issues = issues;
        this.reports = reports;
        this.impacts = impacts;
        this.clock = clock;
    }

    @Override
    public ImpactView confirm(UserId actor, IssueId issueId) {
        return change(actor, issueId, true);
    }

    @Override
    public ImpactView withdraw(UserId actor, IssueId issueId) {
        return change(actor, issueId, false);
    }

    private ImpactView change(UserId actor, IssueId issueId, boolean confirmed) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return transactions.inTransaction(() -> {
            Issue issue = issues.findForUpdate(issueId).orElseThrow(NotFoundException::new);
            BuildingAccess access = BuildingAccess.of(actor, memberships.findByUser(actor), now);
            IssueSharing.requireMayChangeImpact(access, issue, reports.existsByIssueAndAuthor(issueId, actor));

            boolean already = impacts.exists(issueId, actor);
            if (confirmed && !already) {
                impacts.add(issueId, actor, now);
            } else if (!confirmed && already) {
                impacts.remove(issueId, actor);
            }
            return new ImpactView(impacts.count(issueId, now), confirmed);
        });
    }
}
