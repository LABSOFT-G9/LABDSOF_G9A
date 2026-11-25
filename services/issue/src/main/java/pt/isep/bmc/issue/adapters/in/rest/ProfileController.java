package pt.isep.bmc.issue.adapters.in.rest;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.bmc.issue.adapters.in.rest.dto.ProfileResponse;
import pt.isep.bmc.issue.application.port.in.GetProfileQuery;
import pt.isep.bmc.issue.application.query.Profile;
import pt.isep.bmc.issue.domain.membership.PlatformRole;

/**
 * {@code GET /me} (technical design 4.2): the client loads its session from it
 * and navigates to the edge plugin's login URI on {@code 401} (ADR-009).
 */
@RestController
class ProfileController {

    private final GetProfileQuery getProfile;

    ProfileController(GetProfileQuery getProfile) {
        this.getProfile = getProfile;
    }

    @GetMapping(path = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    ProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        Profile profile = getProfile.get(Actors.identity(jwt));
        return new ProfileResponse(
                profile.userId().value(),
                profile.displayName().orElse(null),
                profile.email().orElse(null),
                profile.platformRoles().stream().map(PlatformRole::value).sorted().toList(),
                profile.memberships().stream()
                        .map(m -> new ProfileResponse.MembershipResponse(
                                m.membershipId().toString(),
                                m.buildingId().toString(),
                                m.buildingName(),
                                m.role().value()))
                        .toList());
    }
}
