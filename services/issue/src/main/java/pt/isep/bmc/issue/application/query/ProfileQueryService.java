package pt.isep.bmc.issue.application.query;

import java.time.Clock;
import pt.isep.bmc.issue.application.port.in.GetProfileQuery;
import pt.isep.bmc.issue.application.port.out.ProfileReadModel;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * {@code GET /me}. A user with no profile or memberships gets an empty list:
 * a global account grants no access by itself (ADR-005).
 */
public final class ProfileQueryService implements GetProfileQuery {

    private final TransactionRunner transactions;
    private final ProfileReadModel readModel;
    private final Clock clock;

    public ProfileQueryService(TransactionRunner transactions, ProfileReadModel readModel, Clock clock) {
        this.transactions = transactions;
        this.readModel = readModel;
        this.clock = clock;
    }

    @Override
    public Profile get(UserId actor) {
        return transactions.readOnly(() -> new Profile(
                actor, readModel.findDisplayName(actor), readModel.findActiveMemberships(actor, clock.instant())));
    }
}
