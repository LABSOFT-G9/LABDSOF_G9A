package pt.isep.bmc.issue.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import pt.isep.bmc.issue.application.query.MembershipView;
import pt.isep.bmc.issue.domain.membership.UserId;

public interface ProfileReadModel {

    /** Display name stored in the user's profile, if the profile exists. */
    Optional<String> findDisplayName(UserId userId);

    /** Memberships active at {@code now}, with their building names. */
    List<MembershipView> findActiveMemberships(UserId userId, Instant now);
}
