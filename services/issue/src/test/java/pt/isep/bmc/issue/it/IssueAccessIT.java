package pt.isep.bmc.issue.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.issue.support.Fixtures.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pt.isep.bmc.issue.support.Api.Response;
import pt.isep.bmc.issue.support.IntegrationTestSupport;

/**
 * US-02 permissions, checked in the backend (security access matrix): who
 * lists and reads which issues and history, and who may triage.
 */
class IssueAccessIT extends IntegrationTestSupport {

    static final String REASON = "InternalMarker: the resident in 2B says the socket sparks";

    @Test
    void anUndecidedIssueShowsNoCategoryOrUrgencyToAnyone() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);

        for (String sub : List.of(ADMIN_A, RESIDENT_A1)) {
            Response response = api.get("/issues/" + issueId, tokens.token(sub));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.header("ETag")).isEqualTo("\"1\"");
            Map<String, Object> issue = response.json();
            assertThat(issue).containsEntry("issueId", issueId).containsEntry("buildingId", BUILDING_A)
                    .containsEntry("buildingName", "Synthetic Building A").containsEntry("areaLabel", "Garage level -1")
                    .containsEntry("state", "received").containsEntry("version", 1)
                    .containsKeys("createdAt", "triage", "suggestion");
            assertThat(issue.get("triage")).as("undecided stays pending, never invented").isNull();
            assertThat(issue.get("suggestion")).isNull();
            assertThat(response.body()).doesNotContain("dripping").doesNotContain(RESIDENT_A1);
        }
    }

    @Test
    void theAdministratorSeesTheDecisionWithItsReasonAndTheReporterWithoutIt() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        assertThat(triage(ADMIN_A, issueId, 1, "electrical", "critical", REASON).status()).isEqualTo(200);

        Map<String, Object> forAdmin = api.get("/issues/" + issueId, tokens.token(ADMIN_A)).json();
        assertThat(forAdmin).containsEntry("state", "triaged").containsEntry("version", 2);
        assertThat(IssueTriageIT.triageOf(forAdmin)).containsEntry("category", "electrical")
                .containsEntry("urgency", "critical").containsEntry("reason", REASON)
                .containsEntry("decisionSource", "human").containsKey("decidedAt");

        Response forReporter = api.get("/issues/" + issueId, tokens.token(RESIDENT_A1));
        assertThat(forReporter.status()).isEqualTo(200);
        assertThat(forReporter.json()).containsEntry("state", "triaged").containsEntry("version", 2);
        assertThat(IssueTriageIT.triageOf(forReporter.json())).containsEntry("category", "electrical")
                .containsEntry("urgency", "critical").containsEntry("decisionSource", "human");
        assertThat(IssueTriageIT.triageOf(forReporter.json()).get("reason")).isNull();
        assertThat(forReporter.body()).doesNotContain("InternalMarker");
    }

    @ParameterizedTest
    @ValueSource(strings = {RESIDENT_A2, RESIDENT_B1, ADMIN_B, REVOKED_ADMIN_A, CONTRACTOR, STRANGER})
    void nobodyElseCanReadTheIssueItsHistoryOrTriageIt(String sub) {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        triage(ADMIN_A, issueId, 1, "electrical", "critical", REASON);

        for (Response response : List.of(
                api.get("/issues/" + issueId, tokens.token(sub)),
                api.get("/issues/" + issueId + "/history", tokens.token(sub)),
                triage(sub, issueId, 2, "plumbing", "low", "Trying to change it"),
                // Not even a hint through a stale version or an invalid body.
                triage(sub, issueId, 1, "nonsense", null, null))) {
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.json()).containsEntry("code", "NOT_FOUND");
            assertThat(response.body()).doesNotContain("InternalMarker").doesNotContain("electrical");
        }
        assertThat(count("triage_decisions")).isEqualTo(1);
    }

    @Test
    void theReporterMaySeeTheIssueButNotTriageIt() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);

        Response response = triage(RESIDENT_A1, issueId, 1, "plumbing", "high", "I think it is urgent");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.header("Content-Type")).startsWith("application/problem+json");
        assertThat(response.json()).containsEntry("code", "FORBIDDEN").containsKey("correlationId");
        assertThat(count("triage_decisions")).isZero();
        assertThat(jdbc.sql("SELECT state FROM issues WHERE id = :id").param("id", issueId).query(String.class).single())
                .isEqualTo("received");
    }

    @Test
    void anAdministratorTriagesAnIssueTheyReportedOnBehalf() {
        String issueId = submitIssue(ADMIN_A, BUILDING_A, AREA_A_UNIT);
        assertThat(triage(ADMIN_A, issueId, 1, "structural", "normal", "Crack seen on inspection").status()).isEqualTo(200);
    }

    @Test
    void listsShowManagedBuildingsOrOwnReportsOnlyAndNeverAReason() {
        String a1 = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        String a2 = submitIssue(RESIDENT_A2, BUILDING_A, AREA_A_HALL);
        String b1 = submitIssue(RESIDENT_B1, BUILDING_B, AREA_B_GARAGE);
        String byAdmin = submitIssue(ADMIN_A, BUILDING_A, AREA_A_UNIT);
        triage(ADMIN_A, a1, 1, "electrical", "critical", REASON);

        assertThat(listIds(ADMIN_A, "")).containsExactly(byAdmin, a2, a1);
        assertThat(listIds(ADMIN_B, "")).containsExactly(b1);
        assertThat(listIds(RESIDENT_A1, "")).containsExactly(a1);
        assertThat(listIds(RESIDENT_A2, "")).containsExactly(a2);
        assertThat(listIds(CONTRACTOR, "")).isEmpty();
        assertThat(listIds(REVOKED_ADMIN_A, "")).isEmpty();
        assertThat(listIds(STRANGER, "")).isEmpty();
        // A filter narrows, never widens.
        assertThat(listIds(ADMIN_A, "?buildingId=" + BUILDING_B)).isEmpty();
        assertThat(listIds(ADMIN_A, "?buildingId=" + BUILDING_A)).hasSize(3);
        assertThat(listIds(RESIDENT_A1, "?buildingId=" + BUILDING_B)).isEmpty();

        Response forAdmin = api.get("/issues", tokens.token(ADMIN_A));
        assertThat(forAdmin.body()).doesNotContain("InternalMarker").doesNotContain("dripping");
        Map<String, Object> triaged = items(forAdmin.json()).stream()
                .filter(item -> a1.equals(item.get("issueId"))).findFirst().orElseThrow();
        assertThat(triaged).containsEntry("state", "triaged").containsEntry("version", 2)
                .containsEntry("areaLabel", "Garage level -1");
        assertThat(IssueTriageIT.triageOf(triaged)).containsEntry("category", "electrical").containsEntry("reason", null);
        Map<String, Object> undecided = items(forAdmin.json()).stream()
                .filter(item -> a2.equals(item.get("issueId"))).findFirst().orElseThrow();
        assertThat(undecided.get("triage")).isNull();
    }

    @Test
    void theListIsPagedNewestFirst() {
        String first = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        String second = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_HALL);
        String third = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_UNIT);

        Map<String, Object> page0 = api.get("/issues?size=2", tokens.token(ADMIN_A)).json();
        assertThat(ids(page0)).containsExactly(third, second);
        assertThat(page0).containsEntry("page", 0).containsEntry("size", 2).containsEntry("hasMore", true);
        Map<String, Object> page1 = api.get("/issues?size=2&page=1", tokens.token(ADMIN_A)).json();
        assertThat(ids(page1)).containsExactly(first);
        assertThat(page1).containsEntry("hasMore", false);

        assertThat(api.get("/issues?size=101", tokens.token(ADMIN_A)).status()).isEqualTo(400);
        assertThat(api.get("/issues?page=-1", tokens.token(ADMIN_A)).status()).isEqualTo(400);
        assertThat(api.get("/issues?buildingId=x", tokens.token(ADMIN_A)).status()).isEqualTo(400);
    }

    @Test
    void historyShowsEverythingToTheAdministratorAndOnlyPublicEntriesWithoutReasonsToTheReporter() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        // An internal entry, as a recorded suggestion will write (US-03-03): fixture until then.
        jdbc.sql("""
                INSERT INTO issue_history (issue_id, sequence, type, visibility, actor_role, issue_version, occurred_at)
                VALUES (:id, 2, 'triage_suggested', 'internal', 'system', 2, UTC_TIMESTAMP(6))
                """).param("id", issueId).update();
        jdbc.sql("UPDATE issues SET state = 'in_triage', version = 2 WHERE id = :id").param("id", issueId).update();
        assertThat(triage(ADMIN_A, issueId, 2, "electrical", "critical", REASON).status()).isEqualTo(200);

        List<Map<String, Object>> forAdmin = items(api.get("/issues/" + issueId + "/history", tokens.token(ADMIN_A)).json());
        assertThat(forAdmin).extracting(e -> e.get("sequence")).containsExactly(1, 2, 3);
        assertThat(forAdmin).extracting(e -> e.get("type"))
                .containsExactly("report_received", "triage_suggested", "triage_decided");
        assertThat(forAdmin).extracting(e -> e.get("visibility")).containsExactly("public", "internal", "public");
        assertThat(forAdmin).extracting(e -> e.get("actorRole")).containsExactly("resident", "system", "administrator");
        assertThat(forAdmin.get(0)).containsEntry("category", null).containsEntry("reason", null).containsKey("occurredAt");
        assertThat(forAdmin.get(2)).containsEntry("category", "electrical").containsEntry("urgency", "critical")
                .containsEntry("reason", REASON);

        Response reporter = api.get("/issues/" + issueId + "/history", tokens.token(RESIDENT_A1));
        assertThat(reporter.status()).isEqualTo(200);
        List<Map<String, Object>> forReporter = items(reporter.json());
        assertThat(forReporter).extracting(e -> e.get("type")).containsExactly("report_received", "triage_decided");
        assertThat(forReporter).extracting(e -> e.get("sequence")).containsExactly(1, 3);
        assertThat(forReporter.get(1)).containsEntry("category", "electrical").containsEntry("urgency", "critical")
                .containsEntry("reason", null).containsEntry("actorRole", "administrator");
        // Roles, never identities, and nothing internal.
        assertThat(reporter.body()).doesNotContain("InternalMarker").doesNotContain("triage_suggested")
                .doesNotContain(ADMIN_A);
        assertThat(api.get("/issues/" + issueId + "/history", tokens.token(ADMIN_A)).body()).doesNotContain(ADMIN_A);
    }

    @Test
    void unknownOrMalformedIssueIdsAreNotFoundAndEveryEndpointNeedsAToken() {
        for (String path : List.of("/issues/" + UUID.randomUUID(), "/issues/not-a-uuid",
                "/issues/" + UUID.randomUUID() + "/history", "/issues/not-a-uuid/history")) {
            assertThat(api.get(path, tokens.token(ADMIN_A)).status()).isEqualTo(404);
        }
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        for (String path : List.of("/issues", "/issues/" + issueId, "/issues/" + issueId + "/history")) {
            assertThat(api.get(path, null).status()).isEqualTo(401);
            assertThat(api.get(path, tokens.forged(ADMIN_A)).status()).isEqualTo(401);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void anIssueStateCannotBeEditedDirectly(String method) {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        Response response = api.method(method, "/issues/" + issueId, tokens.token(ADMIN_A), "{\"state\":\"resolved\"}");
        assertThat(response.status()).isEqualTo(405);
        assertThat(jdbc.sql("SELECT state FROM issues WHERE id = :id").param("id", issueId).query(String.class).single())
                .isEqualTo("received");
    }

    private List<String> listIds(String sub, String query) {
        Response response = api.get("/issues" + query, tokens.token(sub));
        assertThat(response.status()).isEqualTo(200);
        return ids(response.json());
    }

    private static List<String> ids(Map<String, Object> page) {
        return items(page).stream().map(item -> (String) item.get("issueId")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }
}
