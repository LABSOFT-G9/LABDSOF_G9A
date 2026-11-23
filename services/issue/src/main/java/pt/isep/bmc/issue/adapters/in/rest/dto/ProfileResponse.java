package pt.isep.bmc.issue.adapters.in.rest.dto;

import java.util.List;

/** {@code GET /me}: the caller's profile and active memberships. */
public record ProfileResponse(String userId, String displayName, List<MembershipResponse> memberships) {

    public record MembershipResponse(String membershipId, String buildingId, String buildingName, String role) {
    }
}
