package pt.isep.bmc.issue.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.issue.support.Fixtures.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.support.Api.Response;
import pt.isep.bmc.issue.support.IntegrationTestSupport;

/** What the report form needs: the caller's buildings and their areas. */
class ProfileAndAreasIT extends IntegrationTestSupport {

    @Test
    void meListsActiveMembershipsOnly() {
        Map<String, Object> me = api.get("/me", tokens.token(RESIDENT_A1)).json();
        assertThat(me).containsEntry("userId", RESIDENT_A1).containsEntry("displayName", "Synthetic Resident A1");
        assertThat(me.get("memberships")).isEqualTo(List.of(Map.of(
                "membershipId", "0199f2a0-0000-7000-8000-00000000d001",
                "buildingId", BUILDING_A, "buildingName", "Synthetic Building A", "role", "resident")));

        assertThat(api.get("/me", tokens.token(REVOKED_ADMIN_A)).json().get("memberships")).isEqualTo(List.of());
        assertThat(api.get("/me", tokens.token(CONTRACTOR)).json().get("memberships")).isEqualTo(List.of());
        Map<String, Object> stranger = api.get("/me", tokens.token(STRANGER)).json();
        assertThat(stranger.get("displayName")).isNull();
        assertThat(stranger.get("email")).isNull();
        assertThat(stranger.get("platformRoles")).isEqualTo(List.of());
        assertThat(stranger.get("memberships")).isEqualTo(List.of());
    }

    @Test
    void mePassesOnWhatTheIdentityProviderSaysOfTheAccount() {
        Map<String, Object> staff = api.get("/me", tokens.token(STRANGER, claims -> claims
                .claim("name", "Synthetic Newcomer")
                .claim("email", "synthetic.newcomer@bmc.example")
                .claim("realm_access", Map.of("roles", List.of("default-roles-bmc", "user", "platform_staff"))))).json();
        // No profile row yet: the name comes from the token. Unknown realm roles are left out.
        assertThat(staff).containsEntry("displayName", "Synthetic Newcomer")
                .containsEntry("email", "synthetic.newcomer@bmc.example")
                .containsEntry("platformRoles", List.of("platform_staff"))
                .containsEntry("memberships", List.of());

        // A stored profile name wins over the token's; a plain user has no platform role.
        Map<String, Object> resident = api.get("/me", tokens.token(RESIDENT_A1, claims -> claims
                .claim("name", "Someone Else")
                .claim("preferred_username", "synthetic.resident.a1@bmc.example")
                .claim("realm_access", Map.of("roles", List.of("user"))))).json();
        assertThat(resident).containsEntry("displayName", "Synthetic Resident A1")
                .containsEntry("platformRoles", List.of());
        assertThat(resident.get("email")).isNull();

        // Without `name`, the username is shown; malformed claims are ignored, never an error.
        Map<String, Object> odd = api.get("/me", tokens.token(STRANGER, claims -> claims
                .claim("preferred_username", "synthetic.user")
                .claim("email", 42)
                .claim("realm_access", "platform_staff"))).json();
        assertThat(odd).containsEntry("displayName", "synthetic.user").containsEntry("platformRoles", List.of());
        assertThat(odd.get("email")).isNull();
    }

    @Test
    void platformRolesInATokenGrantNothingInThisService() {
        String staff = tokens.token(PLATFORM_STAFF, claims -> claims
                .claim("realm_access", Map.of("roles", List.of("platform_staff"))));
        String reportId = (String) api.post("/reports", tokens.token(RESIDENT_A1), java.util.UUID.randomUUID().toString(),
                report(BUILDING_A, AREA_A_GARAGE, TEXT)).json().get("reportId");

        assertThat(api.get("/reports/" + reportId, staff).status()).isEqualTo(404);
        assertThat(api.get("/reports", staff).json().get("items")).isEqualTo(List.of());
        assertThat(api.get("/buildings/" + BUILDING_A + "/areas", staff).status()).isEqualTo(404);
        assertThat(api.post("/reports", staff, java.util.UUID.randomUUID().toString(),
                report(BUILDING_A, AREA_A_GARAGE, TEXT)).status()).isEqualTo(404);
    }

    @Test
    void membersSeeTheAreasOfTheirBuildingOthersGetNotFound() {
        Response areas = api.get("/buildings/" + BUILDING_A + "/areas", tokens.token(RESIDENT_A2));
        assertThat(areas.status()).isEqualTo(200);
        assertThat(areas.jsonList().stream().map(a -> (String) ((Map<?, ?>) a).get("areaId")).toList())
                .containsExactlyInAnyOrder(AREA_A_GARAGE, AREA_A_HALL, AREA_A_UNIT);
        assertThat(api.get("/buildings/" + BUILDING_A + "/areas", tokens.token(ADMIN_A)).status()).isEqualTo(200);

        for (String sub : List.of(RESIDENT_B1, CONTRACTOR, REVOKED_ADMIN_A, STRANGER)) {
            assertThat(api.get("/buildings/" + BUILDING_A + "/areas", tokens.token(sub)).status()).isEqualTo(404);
        }
        assertThat(api.get("/buildings/nope/areas", tokens.token(RESIDENT_A1)).status()).isEqualTo(404);
    }
}
