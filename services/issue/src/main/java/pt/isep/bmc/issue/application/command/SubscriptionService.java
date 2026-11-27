package pt.isep.bmc.issue.application.command;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.application.port.in.ChangeSubscriptionUseCase;
import pt.isep.bmc.issue.application.port.out.IssueRepository;
import pt.isep.bmc.issue.application.port.out.MembershipRepository;
import pt.isep.bmc.issue.application.port.out.ReportRepository;
import pt.isep.bmc.issue.application.port.out.SubscriptionRepository;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueSharing;
import pt.isep.bmc.issue.domain.membership.BuildingAccess;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * Turns the caller's own notifications for an issue on or off (US-04). Anyone
 * who may see the issue may do it. The issue row is locked for the check and
 * the write, so a repeated request leaves one subscription. The issue itself
 * is never written, and impact is not touched.
 */
public final class SubscriptionService implements ChangeSubscriptionUseCase {

    private final TransactionRunner transactions;
    private final MembershipRepository memberships;
    private final IssueRepository issues;
    private final ReportRepository reports;
    private final SubscriptionRepository subscriptions;
    private final Clock clock;

    public SubscriptionService(
            TransactionRunner transactions,
            MembershipRepository memberships,
            IssueRepository issues,
            ReportRepository reports,
            SubscriptionRepository subscriptions,
            Clock clock) {
        this.transactions = transactions;
        this.memberships = memberships;
        this.issues = issues;
        this.reports = reports;
        this.subscriptions = subscriptions;
        this.clock = clock;
    }

    @Override
    public boolean subscribe(UserId actor, IssueId issueId) {
        return change(actor, issueId, true);
    }

    @Override
    public boolean unsubscribe(UserId actor, IssueId issueId) {
        return change(actor, issueId, false);
    }

    private boolean change(UserId actor, IssueId issueId, boolean subscribed) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return transactions.inTransaction(() -> {
            Issue issue = issues.findForUpdate(issueId).orElseThrow(NotFoundException::new);
            BuildingAccess access = BuildingAccess.of(actor, memberships.findByUser(actor), now);
            IssueSharing.requireVisible(access, issue, reports.existsByIssueAndAuthor(issueId, actor));

            boolean already = subscriptions.exists(issueId, actor);
            if (subscribed && !already) {
                subscriptions.add(issueId, actor, now);
            } else if (!subscribed && already) {
                subscriptions.remove(issueId, actor);
            }
            return subscribed;
        });
    }
}
