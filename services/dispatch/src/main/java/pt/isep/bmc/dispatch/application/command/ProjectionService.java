package pt.isep.bmc.dispatch.application.command;

import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;
import pt.isep.bmc.dispatch.application.port.in.ApplyIssueStateChangedUseCase;
import pt.isep.bmc.dispatch.application.port.in.ApplyMembershipChangeUseCase;
import pt.isep.bmc.dispatch.application.port.out.DuplicateProjectionRowException;
import pt.isep.bmc.dispatch.application.port.out.IssueProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.MembershipProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.TransactionConflictException;
import pt.isep.bmc.dispatch.application.port.out.TransactionRunner;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.projection.ProjectedMembership;

/**
 * Keeps Dispatch's projections of Issue's data (technical design 4.3), the
 * only writer of both. An event is applied by version: a newer one replaces
 * the row, an older or repeated one is ignored, so redeliveries, replays and
 * out-of-order arrival leave the same result. Each event is one transaction
 * holding the row's lock.
 *
 * <p>Deduplication by event ID is the messaging starter's (EN-02-02); applying
 * by version makes a duplicate harmless before it is wired.
 */
public final class ProjectionService implements ApplyIssueStateChangedUseCase, ApplyMembershipChangeUseCase {

    /** Attempts per event when concurrent transactions get in the way; each one commits nothing until it wins. */
    static final int MAX_ATTEMPTS = 10;

    private final TransactionRunner transactions;
    private final IssueProjectionRepository issues;
    private final MembershipProjectionRepository memberships;

    public ProjectionService(
            TransactionRunner transactions, IssueProjectionRepository issues, MembershipProjectionRepository memberships) {
        this.transactions = transactions;
        this.issues = issues;
        this.memberships = memberships;
    }

    @Override
    public boolean apply(ProjectedIssue incoming) {
        return upsert(() -> issues.findForUpdate(incoming.issueId()), incoming, ProjectedIssue::replaces,
                issues::insert, issues::update);
    }

    @Override
    public boolean apply(ProjectedMembership incoming) {
        return upsert(() -> memberships.findForUpdate(incoming.membershipId()), incoming,
                ProjectedMembership::replaces, memberships::insert, memberships::update);
    }

    /**
     * Inserts the first version of a row, or replaces an older one. When first
     * events for the same row race, the loser's insert fails, or InnoDB picks it
     * as a deadlock victim (the locking read of a missing row takes a gap lock);
     * it is then applied again, by version, against the winner's row.
     */
    private <T> boolean upsert(
            Supplier<Optional<T>> lockStored, T incoming, BiPredicate<T, T> replaces, Consumer<T> insert,
            Consumer<T> update) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.inTransaction(() -> write(lockStored, incoming, replaces, insert, update));
            } catch (DuplicateProjectionRowException | TransactionConflictException raced) {
                if (attempt == MAX_ATTEMPTS) {
                    throw raced;
                }
            }
        }
    }

    private static <T> boolean write(
            Supplier<Optional<T>> lockStored, T incoming, BiPredicate<T, T> replaces, Consumer<T> insert,
            Consumer<T> update) {
        Optional<T> stored = lockStored.get();
        if (stored.isEmpty()) {
            insert.accept(incoming);
            return true;
        }
        if (replaces.test(incoming, stored.get())) {
            update.accept(incoming);
            return true;
        }
        return false;
    }
}
