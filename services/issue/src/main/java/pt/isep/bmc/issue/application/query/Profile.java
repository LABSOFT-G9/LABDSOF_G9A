package pt.isep.bmc.issue.application.query;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import pt.isep.bmc.issue.domain.membership.PlatformRole;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * The caller's profile and active memberships ({@code GET /me}).
 *
 * @param displayName   the name stored in this service's profile, else the identity provider's
 * @param email         from the identity provider; this service does not store it
 * @param platformRoles from the identity provider; building roles are the memberships
 */
public record Profile(
        UserId userId,
        Optional<String> displayName,
        Optional<String> email,
        Set<PlatformRole> platformRoles,
        List<MembershipView> memberships) {

    public Profile {
        platformRoles = Set.copyOf(platformRoles);
        memberships = List.copyOf(memberships);
    }
}
