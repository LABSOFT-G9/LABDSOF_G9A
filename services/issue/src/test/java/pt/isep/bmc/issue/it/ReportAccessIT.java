package pt.isep.bmc.issue.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.issue.support.Fixtures.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pt.isep.bmc.issue.support.Api.Response;
import pt.isep.bmc.issue.support.IntegrationTestSupport;

/**
 * US-01 permissions, checked in the backend for submission, listing, reading
 * and modification (security access matrix; contract annex W3/W4).
 */
class ReportAccessIT extends IntegrationTestSupport {

    String submit(String sub, String building, String area) {
        Response response = api.post("/reports", tokens.token(sub), UUID.randomUUID().toString(),
                report(building, area, TEXT));
        assertThat(response.status()).isEqualTo(201);
        return (String) response.json().get("reportId");
    }

    @Test
    void authorReadsOwnReportWithTextAndStatus() {
        String reportId = submit(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        Response response = api.get("/reports/" + reportId, tokens.token(RESIDENT_A1));

        assertThat(response.status()).isEqualTo(200);
        Map<String, Object> body = response.json();
        assertThat(body).containsEntry("text", TEXT).containsEntry("authoredByMe", true)
                .containsEntry("areaLabel", "Garage level -1").containsEntry("source", "resident")
                .containsEntry("issue", Map.of("state", "received", "version", 1))
                .doesNotContainKey("authorUserId");
    }

    @Test
    void buildingAdministratorReadsTheReport() {
        String reportId = submit(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        Response response = api.get("/reports/" + reportId, tokens.token(ADMIN_A));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json()).containsEntry("text", TEXT).containsEntry("authoredByMe", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {RESIDENT_A2, RESIDENT_B1, ADMIN_B, REVOKED_ADMIN_A, CONTRACTOR, STRANGER})
    void nobodyElseCanReadTheRawText(String sub) {
        String reportId = submit(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        Response response = api.get("/reports/" + reportId, tokens.token(sub));
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("dripping");
        assertThat(response.json()).containsEntry("code", "NOT_FOUND");
    }

    @Test
    void unknownOrMalformedReportIdIsNotFound() {
        assertThat(api.get("/reports/" + UUID.randomUUID(), tokens.token(ADMIN_A)).status()).isEqualTo(404);
        assertThat(api.get("/reports/not-a-uuid", tokens.token(ADMIN_A)).status()).isEqualTo(404);
    }

    @Test
    void listsShowOwnReportsOrManagedBuildingsOnlyAndNeverText() {
        String a1 = submit(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        String a2 = submit(RESIDENT_A2, BUILDING_A, AREA_A_HALL);
        String b1 = submit(RESIDENT_B1, BUILDING_B, AREA_B_GARAGE);
        String admin = submit(ADMIN_A, BUILDING_A, AREA_A_UNIT);

        assertThat(listIds(RESIDENT_A1, "")).containsExactly(a1);
        assertThat(listIds(RESIDENT_A2, "")).containsExactly(a2);
        assertThat(listIds(ADMIN_A, "")).containsExactlyInAnyOrder(a1, a2, admin);
        assertThat(listIds(ADMIN_B, "")).containsExactly(b1);
        assertThat(listIds(CONTRACTOR, "")).isEmpty();
        assertThat(listIds(REVOKED_ADMIN_A, "")).isEmpty();
        // A filter narrows, never widens.
        assertThat(listIds(RESIDENT_A1, "?buildingId=" + BUILDING_B)).isEmpty();
        assertThat(listIds(ADMIN_A, "?buildingId=" + BUILDING_B)).isEmpty();

        Response list = api.get("/reports", tokens.token(ADMIN_A));
        assertThat(list.body()).doesNotContain("dripping");
    }

    @Test
    void listIsPagedNewestFirst() {
        String first = submit(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        String second = submit(RESIDENT_A1, BUILDING_A, AREA_A_HALL);
        String third = submit(RESIDENT_A1, BUILDING_A, AREA_A_UNIT);

        Map<String, Object> page0 = api.get("/reports?size=2", tokens.token(RESIDENT_A1)).json();
        assertThat(ids(page0)).containsExactly(third, second);
        assertThat(page0).containsEntry("hasMore", true);
        Map<String, Object> page1 = api.get("/reports?size=2&page=1", tokens.token(RESIDENT_A1)).json();
        assertThat(ids(page1)).containsExactly(first);
        assertThat(page1).containsEntry("hasMore", false);

        assertThat(api.get("/reports?size=101", tokens.token(RESIDENT_A1)).status()).isEqualTo(400);
        assertThat(api.get("/reports?page=-1", tokens.token(RESIDENT_A1)).status()).isEqualTo(400);
        assertThat(api.get("/reports?buildingId=x", tokens.token(RESIDENT_A1)).status()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void reportsCannotBeModifiedOrDeleted(String method) {
        String reportId = submit(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        Response response = api.method(method, "/reports/" + reportId, tokens.token(RESIDENT_A1),
                "{\"text\":\"Changed text that is long enough\"}");
        assertThat(response.status()).isEqualTo(405);
        assertThat(api.get("/reports/" + reportId, tokens.token(RESIDENT_A1)).json()).containsEntry("text", TEXT);
        assertThat(count("reports")).isEqualTo(1);
    }

    @Test
    void everyEndpointNeedsAValidToken() {
        String reportId = submit(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        List<String> rejected = List.of(
                tokens.forged(RESIDENT_A1),
                tokens.token(RESIDENT_A1, c -> c.issuer("http://evil.test/realms/bmc")),
                tokens.token(RESIDENT_A1, c -> c.audience("another-api")),
                tokens.token(RESIDENT_A1, c -> c.expirationTime(Date.from(Instant.now().minus(Duration.ofMinutes(5))))),
                tokens.token(" ", c -> { }),
                "not.a.jwt",
                "eyJhbGciOiJub25lIn0.eyJzdWIiOiIwMTk5ZjJhMC0wMDAwLTcwMDAtODAwMC0wMDAwMDAwMDAxMDEifQ.");
        for (String token : rejected) {
            for (Response response : List.of(
                    api.get("/reports/" + reportId, token),
                    api.get("/reports", token),
                    api.get("/me", token),
                    api.post("/reports", token, UUID.randomUUID().toString(), report(BUILDING_A, AREA_A_GARAGE, TEXT)))) {
                assertThat(response.status()).isEqualTo(401);
                assertThat(response.header("WWW-Authenticate")).startsWith("Bearer");
                assertThat(response.json()).containsEntry("code", "UNAUTHENTICATED");
            }
        }
        Response anonymous = api.get("/reports/" + reportId, null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.header("Location")).isNull();
        assertThat(count("reports")).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private List<String> listIds(String sub, String query) {
        Response response = api.get("/reports" + query, tokens.token(sub));
        assertThat(response.status()).isEqualTo(200);
        return ids(response.json());
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items")).stream()
                .peek(item -> assertThat(item).doesNotContainKey("text"))
                .map(item -> (String) item.get("reportId"))
                .toList();
    }
}
