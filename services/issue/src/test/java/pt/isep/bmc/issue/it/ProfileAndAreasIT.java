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
        assertThat(stranger.get("memberships")).isEqualTo(List.of());
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
