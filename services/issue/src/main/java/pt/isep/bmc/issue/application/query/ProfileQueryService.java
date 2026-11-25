package pt.isep.bmc.issue.application.query;

import java.time.Clock;
import pt.isep.bmc.issue.application.port.in.GetProfileQuery;
import pt.isep.bmc.issue.application.port.out.ProfileReadModel;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;

/**
 * {@code GET /me}. A user with no profile or memberships gets an empty list:
 * a global account grants no access by itself (ADR-005). Memberships come only
 * from this service's data; the email and the platform roles are passed on
 * from the identity provider, and so is the name until a profile exists here.
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
    public Profile get(CallerIdentity caller) {
        return transactions.readOnly(() -> new Profile(
                caller.userId(),
                readModel.findDisplayName(caller.userId()).or(caller::displayName),
                caller.email(),
                caller.platformRoles(),
                readModel.findActiveMemberships(caller.userId(), clock.instant())));
    }
}
