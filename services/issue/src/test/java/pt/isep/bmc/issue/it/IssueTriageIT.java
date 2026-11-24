package pt.isep.bmc.issue.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.issue.support.Fixtures.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pt.isep.bmc.issue.support.Api.Response;
import pt.isep.bmc.issue.support.IntegrationTestSupport;
import tools.jackson.databind.json.JsonMapper;

/**
 * US-02-01 on real MySQL: manual triage with {@code If-Match}, the state
 * table, safe retries and what is written in the same transaction (technical
 * design 3.1, 3.3 and 5; contract examples 1.2).
 */
class IssueTriageIT extends IntegrationTestSupport {

    static final String REASON = "Water near an electrical fitting";

    @Test
    void anAdministratorRecordsADecisionWithActorTimeReasonAndVersion() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);

        Response response = triage(ADMIN_A, issueId, 1, "plumbing", "high", "  " + REASON + "  ");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("ETag")).isEqualTo("\"2\"");
        assertThat(response.json()).isEqualTo(Map.of("issueId", issueId, "state", "triaged", "version", 2,
                "category", "plumbing", "urgency", "high", "decisionSource", "human"));

        Map<String, Object> decision = jdbc.sql("SELECT * FROM triage_decisions WHERE issue_id = :id")
                .param("id", issueId).query().singleRow();
        assertThat(decision).containsEntry("resulting_version", 2L).containsEntry("category", "plumbing")
                .containsEntry("urgency", "high").containsEntry("reason", REASON).containsEntry("decided_by", ADMIN_A);
        assertThat(decision.get("decided_at")).isNotNull();
        Map<String, Object> issue = jdbc.sql("SELECT state, version, category, urgency FROM issues WHERE id = :id")
                .param("id", issueId).query().singleRow();
        assertThat(issue).containsEntry("state", "triaged").containsEntry("version", 2L)
                .containsEntry("category", "plumbing").containsEntry("urgency", "high");
    }

    @Test
    void theStateChangeAndItsEventsAreWrittenTogetherAndCarryNoReason() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        jdbc.sql("DELETE FROM outbox").update();

        assertThat(triage(ADMIN_A, issueId, 1, "plumbing", "high", REASON).status()).isEqualTo(200);

        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT event_type, routing_key, aggregate_id, aggregate_version, building_id, status, payload
                  FROM outbox ORDER BY event_type
                """).query().listOfRows();
        assertThat(rows).extracting(r -> r.get("event_type")).containsExactly("IssueStateChanged", "IssueTriaged");
        assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("aggregate_id", issueId)
                .containsEntry("aggregate_version", 2L).containsEntry("building_id", BUILDING_A)
                .containsEntry("status", "pending"));
        assertThat(rows.get(0)).containsEntry("routing_key", "issue.IssueStateChanged");

        Map<String, Object> stateChanged = envelope(rows.get(0));
        assertThat(stateChanged).containsEntry("type", "IssueStateChanged").containsEntry("subject", issueId)
                .containsEntry("aggregateversion", 2);
        Map<String, Object> expected = new HashMap<>(Map.of("issueId", issueId, "buildingId", BUILDING_A,
                "state", "triaged", "version", 2, "dispatchable", true, "category", "plumbing", "urgency", "high",
                "areaLabel", "Garage level -1"));
        // No summary was published: the reference is sent as null, never left to a default.
        expected.put("publishedSummaryId", null);
        assertThat(stateChanged.get("data")).isEqualTo(expected);
        assertThat(envelope(rows.get(1)).get("data")).isEqualTo(Map.of("issueId", issueId, "category", "plumbing",
                "urgency", "high", "decisionSource", "human"));
        assertThat(rows).allSatisfy(row -> assertThat((String) row.get("payload").toString()).doesNotContain("Water"));
    }

    @Test
    void aDecisionCanBeCorrectedAndEveryDecisionIsKept() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        triage(ADMIN_A, issueId, 1, "plumbing", "high", REASON);

        Response corrected = triage(ADMIN_A, issueId, 2, "electrical", "critical", "It is the socket, not the pipe");

        assertThat(corrected.status()).isEqualTo(200);
        assertThat(corrected.json()).containsEntry("version", 3).containsEntry("category", "electrical");
        assertThat(count("triage_decisions")).isEqualTo(2);
        Map<String, Object> issue = api.get("/issues/" + issueId, tokens.token(ADMIN_A)).json();
        assertThat(issue).containsEntry("state", "triaged").containsEntry("version", 3);
        assertThat(triageOf(issue)).containsEntry("category", "electrical").containsEntry("urgency", "critical")
                .containsEntry("reason", "It is the socket, not the pipe");
    }

    @Test
    void invalidInputIsRejectedWithEveryFieldAndNothingIsStored() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM idempotency_keys").update();
        String secret = "SecretMarker " + "x".repeat(500);

        Response invalid = triage(ADMIN_A, issueId, 1, "gardening", null, secret);

        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.json()).containsEntry("code", "VALIDATION_FAILED");
        assertThat(invalid.json().get("errors")).isEqualTo(List.of(
                Map.of("field", "category", "reason", "invalid"),
                Map.of("field", "urgency", "reason", "required"),
                Map.of("field", "reason", "reason", "too_long")));
        assertThat(invalid.body()).doesNotContain("SecretMarker");

        assertThat(triage(ADMIN_A, issueId, 1, "plumbing", "high", " ").json().get("errors"))
                .isEqualTo(List.of(Map.of("field", "reason", "reason", "required")));
        assertThat(triage(ADMIN_A, issueId, "\"1\"", UUID.randomUUID().toString(), "{}").status()).isEqualTo(400);
        assertThat(triage(ADMIN_A, issueId, "\"1\"", UUID.randomUUID().toString(), "not json").status()).isEqualTo(400);

        assertNothingChanged(issueId);
    }

    @Test
    void theVersionAndTheKeyAreRequiredHeaders() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM idempotency_keys").update();
        Map<String, Object> body = triageBody("plumbing", "high", REASON);

        Response noVersion = triage(ADMIN_A, issueId, null, UUID.randomUUID().toString(), body);
        assertThat(noVersion.status()).isEqualTo(400);
        assertThat(noVersion.json().get("errors")).isEqualTo(List.of(Map.of("field", "If-Match", "reason", "required")));
        // Only the quoted version this service sent as ETag is accepted.
        for (String malformed : List.of("1", "W/\"1\"", "\"0\"", "\"-1\"", "*", "\"1\", \"2\"", "\"abc\"")) {
            Response response = triage(ADMIN_A, issueId, malformed, UUID.randomUUID().toString(), body);
            assertThat(response.status()).as("If-Match: %s", malformed).isEqualTo(400);
            assertThat(response.json().get("errors"))
                    .isEqualTo(List.of(Map.of("field", "If-Match", "reason", "invalid_format")));
        }
        Response noKey = triage(ADMIN_A, issueId, "\"1\"", null, body);
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.json().get("errors")).isEqualTo(List.of(Map.of("field", "Idempotency-Key", "reason", "required")));

        assertNothingChanged(issueId);
    }

    @Test
    void aStaleVersionIsAConflictThatExplainsWhatTheIssueIsNowAndOverwritesNothing() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        triage(ADMIN_A, issueId, 1, "plumbing", "high", REASON);

        Response stale = triage(ADMIN_A, issueId, 1, "electrical", "low", "Based on what I saw before");

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.header("Content-Type")).startsWith("application/problem+json");
        assertThat(stale.json()).containsEntry("code", "VERSION_CONFLICT").containsEntry("currentVersion", 2)
                .containsEntry("currentState", "triaged").containsKey("correlationId");
        assertThat(count("triage_decisions")).isEqualTo(1);
        assertThat(count("issue_history")).isEqualTo(2);
        assertThat(triageOf(api.get("/issues/" + issueId, tokens.token(ADMIN_A)).json()))
                .containsEntry("category", "plumbing").containsEntry("reason", REASON);
    }

    @ParameterizedTest
    @ValueSource(strings = {"in_coordination", "scheduled", "in_progress", "awaiting_validation", "resolved", "cancelled"})
    void triageIsRefusedOnceAnInterventionStartedOrTheIssueIsClosed(String state) {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        triage(ADMIN_A, issueId, 1, "plumbing", "high", REASON);
        // Later states come from Dispatch's events and from closure; set here as a fixture.
        jdbc.sql("UPDATE issues SET state = :state, version = 3 WHERE id = :id")
                .param("state", state).param("id", issueId).update();

        Response response = triage(ADMIN_A, issueId, 3, "electrical", "low", REASON);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json()).containsEntry("code", "INVALID_TRANSITION").containsEntry("currentState", state);
        assertThat(count("triage_decisions")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT version FROM issues WHERE id = :id").param("id", issueId).query(Long.class).single())
                .isEqualTo(3);
    }

    @Test
    void aRetryWithTheSameKeyReturnsTheOriginalResultEvenAfterTheVersionMovedOn() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = triageBody("plumbing", "high", REASON);

        Response first = triage(ADMIN_A, issueId, "\"1\"", key, body);
        Response retry = triage(ADMIN_A, issueId, "\"1\"", key, body);

        assertThat(first.status()).isEqualTo(200);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.json()).isEqualTo(first.json());
        assertThat(retry.header("ETag")).isEqualTo("\"2\"");
        assertThat(count("triage_decisions")).isEqualTo(1);
        assertThat(count("issue_history")).isEqualTo(2);

        // The same key for a different decision, or for the next version, is refused.
        Response reused = triage(ADMIN_A, issueId, "\"1\"", key, triageBody("plumbing", "low", REASON));
        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.json()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
        assertThat(triage(ADMIN_A, issueId, "\"2\"", key, body).status()).isEqualTo(422);
        assertThat(count("triage_decisions")).isEqualTo(1);
    }

    /** Repeated: a lock or deadlock problem shows up as a 503 in some runs only. */
    @RepeatedTest(5)
    void ofSeveralDecisionsBasedOnTheSameVersionExactlyOneWins() throws Exception {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        List<String> categories = List.of("plumbing", "electrical", "lift", "cleaning", "structural", "other");
        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String category = categories.get(i % categories.size());
            calls.add(() -> triage(ADMIN_A, issueId, 1, category, "normal", REASON));
        }

        List<Response> responses = runTogether(calls);

        assertThat(responses).extracting(Response::status).containsOnly(200, 409);
        assertThat(responses).filteredOn(r -> r.status() == 200).hasSize(1);
        assertThat(responses).filteredOn(r -> r.status() == 409).allSatisfy(r -> assertThat(r.json())
                .containsEntry("code", "VERSION_CONFLICT").containsEntry("currentVersion", 2));
        Response winner = responses.stream().filter(r -> r.status() == 200).findFirst().orElseThrow();
        assertThat(count("triage_decisions")).isEqualTo(1);
        assertThat(count("issue_history")).isEqualTo(2);
        assertThat(count("idempotency_keys")).as("report key + the winner's key; losers leave none").isEqualTo(2);
        assertThat(jdbc.sql("SELECT category FROM issues WHERE id = :id").param("id", issueId).query(String.class).single())
                .isEqualTo(winner.json().get("category"));
    }

    @RepeatedTest(5)
    void concurrentRetriesOfOneDecisionStoreItOnce() throws Exception {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = triageBody("lift", "low", REASON);
        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            calls.add(() -> triage(ADMIN_A, issueId, "\"1\"", key, body));
        }

        List<Response> responses = runTogether(calls);

        assertThat(responses).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.json()).containsEntry("version", 2).containsEntry("category", "lift");
        });
        assertThat(count("triage_decisions")).isEqualTo(1);
        assertThat(count("issue_history")).isEqualTo(2);
    }

    @Test
    void unknownIssuesAndMissingCsrfHeaderAreRefused() {
        Map<String, Object> body = triageBody("plumbing", "high", REASON);
        assertThat(triage(ADMIN_A, UUID.randomUUID().toString(), "\"1\"", UUID.randomUUID().toString(), body).status())
                .isEqualTo(404);
        assertThat(triage(ADMIN_A, "not-a-uuid", "\"1\"", UUID.randomUUID().toString(), body).status()).isEqualTo(404);

        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        Map<String, String> headers = new HashMap<>();
        headers.put("If-Match", "\"1\"");
        headers.put("Idempotency-Key", UUID.randomUUID().toString());
        Response noCsrf = api.withoutCsrfHeader()
                .postWithHeaders("/issues/" + issueId + "/triage", tokens.token(ADMIN_A), headers, body);
        assertThat(noCsrf.status()).isEqualTo(403);
        assertThat(noCsrf.json()).containsEntry("code", "CSRF_HEADER_REQUIRED");
        assertThat(count("triage_decisions")).isZero();
    }

    private void assertNothingChanged(String issueId) {
        assertThat(count("triage_decisions")).isZero();
        assertThat(count("issue_history")).isEqualTo(1);
        assertThat(count("outbox")).isZero();
        assertThat(count("idempotency_keys")).isZero();
        Map<String, Object> issue = jdbc.sql("SELECT state, version, category FROM issues WHERE id = :id")
                .param("id", issueId).query().singleRow();
        assertThat(issue).containsEntry("state", "received").containsEntry("version", 1L).containsEntry("category", null);
    }

    private static List<Response> runTogether(List<Callable<Response>> calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
            for (Callable<Response> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
            return responses;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> triageOf(Map<String, Object> issue) {
        return (Map<String, Object>) issue.get("triage");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> envelope(Map<String, Object> outboxRow) {
        return JsonMapper.builder().build().readValue(outboxRow.get("payload").toString(), Map.class);
    }
}
