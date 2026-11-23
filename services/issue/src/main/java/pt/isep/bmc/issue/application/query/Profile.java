package pt.isep.bmc.issue.application.query;

import java.util.List;
import java.util.Optional;
import pt.isep.bmc.issue.domain.membership.UserId;

/** The caller's profile and active memberships ({@code GET /me}). */
public record Profile(UserId userId, Optional<String> displayName, List<MembershipView> memberships) {

    public Profile {
        memberships = List.copyOf(memberships);
    }
}
