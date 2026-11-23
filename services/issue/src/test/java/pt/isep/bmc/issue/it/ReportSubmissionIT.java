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
import tools.jackson.databind.json.JsonMapper;

/** US-01-01: submission, validation and the single transaction (technical design 3.1, 5). */
class ReportSubmissionIT extends IntegrationTestSupport {

    @Test
    void residentGetsReceiptAndEverythingIsStoredTogether() {
        Response response = api.post("/reports", tokens.token(RESIDENT_A1), UUID.randomUUID().toString(),
                report(BUILDING_A, AREA_A_GARAGE, "  " + TEXT + "  "));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.header("ETag")).isEqualTo("\"1\"");
        assertThat(response.header("X-Correlation-Id")).isNotBlank();
        Map<String, Object> body = response.json();
        assertThat(body).containsOnlyKeys("reportId", "issueId", "state", "version", "submittedAt");
        assertThat(body).containsEntry("state", "received").containsEntry("version", 1);
        String reportId = (String) body.get("reportId");
        String issueId = (String) body.get("issueId");
        assertThat(UUID.fromString(reportId).version()).isEqualTo(7);

        assertThat(jdbc.sql("SELECT text, source, author_user_id FROM reports WHERE id = ?").param(reportId)
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3))).single())
                .containsExactly(TEXT, "resident", RESIDENT_A1);
        assertThat(jdbc.sql("SELECT state, version, critical_check_status FROM issues WHERE id = ?").param(issueId)
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3))).single())
                .containsExactly("received", "1", "pending");
        assertThat(jdbc.sql("SELECT type, visibility, actor_role FROM issue_history WHERE issue_id = ?").param(issueId)
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3))).single())
                .containsExactly("report_received", "public", "resident");
    }

    @Test
    void outboxHoldsPendingCloudEventsAndOnlyTriageRequestedCarriesText() {
        Response response = api.post("/reports", tokens.token(RESIDENT_A1), UUID.randomUUID().toString(),
                report(BUILDING_A, AREA_A_UNIT, TEXT));
        String issueId = (String) response.json().get("issueId");

        List<Map<String, Object>> rows = jdbc.sql(
                        "SELECT event_type, routing_key, status, aggregate_id, payload FROM outbox ORDER BY event_type")
                .query((rs, n) -> Map.<String, Object>of(
                        "type", rs.getString(1), "routingKey", rs.getString(2), "status", rs.getString(3),
                        "aggregateId", rs.getString(4), "payload", rs.getString(5)))
                .list();
        assertThat(rows).extracting(r -> r.get("type")).containsExactly("ReportSubmitted", "TriageRequested");
        assertThat(rows).extracting(r -> r.get("routingKey"))
                .containsExactly("issue.ReportSubmitted", "issue.TriageRequested");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("pending");
            assertThat(r.get("aggregateId")).isEqualTo(issueId);
        });

        JsonMapper json = JsonMapper.builder().build();
        @SuppressWarnings("unchecked")
        Map<String, Object> submitted = json.readValue((String) rows.get(0).get("payload"), Map.class);
        assertThat(submitted).containsEntry("specversion", "1.0").containsEntry("source", "issue")
                .containsEntry("subject", issueId).containsEntry("schemaversion", 1)
                .containsEntry("aggregateversion", 1).containsEntry("buildingid", BUILDING_A)
                .containsEntry("correlationid", response.header("X-Correlation-Id"));
        assertThat(data(submitted)).containsOnlyKeys("reportId", "issueId", "areaId");
        assertThat((String) rows.get(0).get("payload")).doesNotContain("dripping");

        @SuppressWarnings("unchecked")
        Map<String, Object> triage = json.readValue((String) rows.get(1).get("payload"), Map.class);
        assertThat(data(triage)).containsEntry("text", TEXT).containsEntry("areaKind", "unit")
                .containsEntry("issueVersion", 1);
    }

    @Test
    void administratorSubmitsOnBehalfOfTheBuilding() {
        Response response = api.post("/reports", tokens.token(ADMIN_A), UUID.randomUUID().toString(),
                report(BUILDING_A, AREA_A_HALL, TEXT));
        assertThat(response.status()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT source FROM reports").query(String.class).single())
                .isEqualTo("administrator_on_behalf");
        assertThat(jdbc.sql("SELECT actor_role FROM issue_history").query(String.class).single())
                .isEqualTo("administrator");
    }

    @ParameterizedTest
    @ValueSource(strings = {RESIDENT_B1, ADMIN_B, CONTRACTOR, STRANGER, REVOKED_ADMIN_A})
    void callersWithoutRightsInTheBuildingGetNotFoundAndNothingIsStored(String sub) {
        Response response = api.post("/reports", tokens.token(sub), UUID.randomUUID().toString(),
                report(BUILDING_A, AREA_A_GARAGE, TEXT));
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json()).containsEntry("code", "NOT_FOUND");
        assertNothingStored();
    }

    @Test
    void unknownBuildingIsNotFound() {
        Response response = api.post("/reports", tokens.token(RESIDENT_A1), UUID.randomUUID().toString(),
                report(UUID.randomUUID().toString(), AREA_A_GARAGE, TEXT));
        assertThat(response.status()).isEqualTo(404);
        assertNothingStored();
    }

    @Test
    void areaOfAnotherBuildingOrUnknownAreaIsRejected() {
        for (String area : List.of(AREA_B_GARAGE, UUID.randomUUID().toString())) {
            Response response = api.post("/reports", tokens.token(RESIDENT_A1), UUID.randomUUID().toString(),
                    report(BUILDING_A, area, TEXT));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.header("Content-Type")).startsWith("application/problem+json");
            assertThat(response.json()).containsEntry("code", "INVALID_AREA");
        }
        assertNothingStored();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "too short", "\t\n  123456789  \n"})
    void blankOrShortTextIsRejected(String text) {
        Response response = api.post("/reports", tokens.token(RESIDENT_A1), UUID.randomUUID().toString(),
                report(BUILDING_A, AREA_A_GARAGE, text));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json()).containsEntry("code", "VALIDATION_FAILED");
        assertNothingStored();
    }

    @Test
    void tooLongTextIsRejectedWithoutEchoingIt() {
        String text = "Secret-marker " + "x".repeat(4_000);
        Response response = api.post("/reports", tokens.token(RESIDENT_A1), UUID.randomUUID().toString(),
                report(BUILDING_A, AREA_A_GARAGE, text));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("Secret-marker");
        assertThat(response.json().get("errors")).isEqualTo(List.of(Map.of("field", "text", "reason", "too_long")));
        assertNothingStored();
    }

    @Test
    void missingOrMalformedFieldsAreRejected() {
        String token = tokens.token(RESIDENT_A1);
        Response missingIds = api.post("/reports", token, UUID.randomUUID().toString(), Map.of("text", TEXT));
        assertThat(missingIds.status()).isEqualTo(400);
        assertThat(missingIds.json().get("errors")).isEqualTo(List.of(
                Map.of("field", "buildingId", "reason", "required"),
                Map.of("field", "areaId", "reason", "required")));

        Response badId = api.post("/reports", token, UUID.randomUUID().toString(),
                report("not-a-uuid", AREA_A_GARAGE, TEXT));
        assertThat(badId.status()).isEqualTo(400);

        Response malformed = api.post("/reports", token, UUID.randomUUID().toString(), "{\"text\": \"Secret-marker");
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.body()).doesNotContain("Secret-marker");

        Response noKey = api.post("/reports", token, null, report(BUILDING_A, AREA_A_GARAGE, TEXT));
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.json().get("errors"))
                .isEqualTo(List.of(Map.of("field", "Idempotency-Key", "reason", "required")));

        Response badKey = api.post("/reports", token, "x".repeat(129), report(BUILDING_A, AREA_A_GARAGE, TEXT));
        assertThat(badKey.status()).isEqualTo(400);
        assertNothingStored();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("data");
    }

    private void assertNothingStored() {
        assertThat(count("reports")).isZero();
        assertThat(count("issues")).isZero();
        assertThat(count("issue_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(count("idempotency_keys")).isZero();
    }
}
