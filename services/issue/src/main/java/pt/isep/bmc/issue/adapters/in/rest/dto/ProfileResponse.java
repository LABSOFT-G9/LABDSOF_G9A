package pt.isep.bmc.issue.adapters.in.rest.dto;

import java.util.List;

/**
 * {@code GET /me}: the caller's profile and active memberships.
 *
 * @param displayName   the profile's name, else the identity provider's; {@code null} if neither exists
 * @param email         the identity provider's address for the account, or {@code null}
 * @param platformRoles the identity provider's platform roles, for example {@code platform_staff}
 */
public record ProfileResponse(
        String userId, String displayName, String email, List<String> platformRoles, List<MembershipResponse> memberships) {

    public record MembershipResponse(String membershipId, String buildingId, String buildingName, String role) {
    }
}
