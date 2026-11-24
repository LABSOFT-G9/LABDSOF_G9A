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
import org.junit.jupiter.params.provider.CsvSource;
import pt.isep.bmc.issue.support.Api.Response;
import pt.isep.bmc.issue.support.IntegrationTestSupport;
import tools.jackson.databind.json.JsonMapper;

/**
 * US-04-01 on real MySQL: the shared summary, who sees a shared issue, impact
 * confirmations and subscriptions (technical design 2.1 and 4.2; access
 * matrix, "published shared summary" and "confirm impact").
 */
class IssueSharingIT extends IntegrationTestSupport {

    static final String SUMMARY = "The garage door on level -1 does not close fully. A repair is being arranged.";

    private Response impact(String method, String sub, String issueId) {
        return api.method(method, "/issues/" + issueId + "/impact", tokens.token(sub), null);
    }

    private Response subscription(String method, String sub, String issueId) {
        return api.method(method, "/issues/" + issueId + "/subscription", tokens.token(sub), null);
    }

    private Map<String, Object> issue(String sub, String issueId) {
        Response response = api.get("/issues/" + issueId, tokens.token(sub));
        assertThat(response.status()).isEqualTo(200);
        return response.json();
    }

    /** An issue reported by resident A1 with a published summary, at version 2. */
    private String sharedIssue() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        assertThat(publish(ADMIN_A, issueId, 1, SUMMARY).status()).isEqualTo(200);
        return issueId;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> envelope(Map<String, Object> outboxRow) {
        return JsonMapper.builder().build().readValue(outboxRow.get("payload").toString(), Map.class);
    }

    // ---- Publication ------------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void anAdministratorPublishesASummaryAndItIsStoredWithItsVersion() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);

        Response response = publish(ADMIN_A, issueId, 1, "  " + SUMMARY + "  ");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("ETag")).isEqualTo("\"2\"");
        assertThat(response.json()).containsEntry("issueId", issueId).containsEntry("version", 2);
        Map<String, Object> summary = (Map<String, Object>) response.json().get("summary");
        assertThat(summary).containsEntry("text", SUMMARY).containsKey("publishedAt");

        Map<String, Object> stored = jdbc.sql("SELECT * FROM issue_summaries WHERE issue_id = :id")
                .param("id", issueId).query().singleRow();
        assertThat(stored).containsEntry("resulting_version", 2L).containsEntry("text", SUMMARY)
                .containsEntry("published_by", ADMIN_A);
        // Only the version moved: publishing decides nothing about the fault.
        Map<String, Object> issue = jdbc.sql(
                        "SELECT state, version, category, urgency, published_summary_id FROM issues WHERE id = :id")
                .param("id", issueId).query().singleRow();
        assertThat(issue).containsEntry("state", "received").containsEntry("version", 2L)
                .containsEntry("category", null).containsEntry("urgency", null)
                .containsEntry("published_summary_id", stored.get("id"));
    }

    @Test
    void thePublicationItsHistoryEntryAndItsEventsAreWrittenTogetherWithoutTheText() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        jdbc.sql("DELETE FROM outbox").update();

        assertThat(publish(ADMIN_A, issueId, 1, SUMMARY).status()).isEqualTo(200);

        String summaryId = jdbc.sql("SELECT id FROM issue_summaries WHERE issue_id = :id").param("id", issueId)
                .query(String.class).single();
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT event_type, routing_key, aggregate_id, aggregate_version, building_id, status, payload
                  FROM outbox ORDER BY event_type
                """).query().listOfRows();
        assertThat(rows).extracting(r -> r.get("event_type")).containsExactly("IssueStateChanged", "IssueSummaryPublished");
        assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("aggregate_id", issueId)
                .containsEntry("aggregate_version", 2L).containsEntry("building_id", BUILDING_A)
                .containsEntry("status", "pending"));

        Map<String, Object> stateChanged = new HashMap<>();
        stateChanged.put("issueId", issueId);
        stateChanged.put("buildingId", BUILDING_A);
        stateChanged.put("state", "received");
        stateChanged.put("version", 2);
        stateChanged.put("dispatchable", false);
        stateChanged.put("category", null);
        stateChanged.put("urgency", null);
        stateChanged.put("areaLabel", "Garage level -1");
        stateChanged.put("publishedSummaryId", summaryId);
        assertThat(envelope(rows.get(0)).get("data")).isEqualTo(stateChanged);
        assertThat(rows.get(1)).containsEntry("routing_key", "issue.IssueSummaryPublished");
        assertThat(envelope(rows.get(1)).get("data")).isEqualTo(Map.of("issueId", issueId, "summaryId", summaryId));
        // The summary travels by reference: its text is read through the API, under authorisation.
        assertThat(rows).allSatisfy(row -> assertThat(row.get("payload").toString()).doesNotContain("garage door"));

        List<Map<String, Object>> history = jdbc.sql(
                        "SELECT sequence, type, visibility, actor_role, issue_version FROM issue_history"
                                + " WHERE issue_id = :id ORDER BY sequence")
                .param("id", issueId).query().listOfRows();
        assertThat(history).hasSize(2);
        assertThat(history.get(1)).containsEntry("sequence", 2).containsEntry("type", "summary_published")
                .containsEntry("visibility", "public").containsEntry("actor_role", "administrator")
                .containsEntry("issue_version", 2L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishingAgainReplacesTheSummaryAndKeepsEveryVersion() {
        String issueId = sharedIssue();

        Response second = publish(ADMIN_A, issueId, 2, "The garage door was repaired this morning.");

        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json()).containsEntry("version", 3);
        assertThat(count("issue_summaries")).isEqualTo(2);
        Map<String, Object> summary = (Map<String, Object>) issue(RESIDENT_A2, issueId).get("summary");
        assertThat(summary).containsEntry("text", "The garage door was repaired this morning.");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
        "NULL | required",
        "'   ' | required",
        "too short | too_short",
        "TOO_LONG | too_long",
        "CONTROL | invalid_characters",
    })
    void anInvalidSummaryIsRefusedAndNothingIsStored(String summary, String reason) {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        String text = "TOO_LONG".equals(summary) ? "x".repeat(501)
                : "CONTROL".equals(summary) ? "A bell rings here\u0007 loudly" : summary;

        Response response = publish(ADMIN_A, issueId, 1, text);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json()).containsEntry("code", "VALIDATION_FAILED");
        assertThat(response.json().get("errors")).isEqualTo(List.of(Map.of("field", "summary", "reason", reason)));
        assertThat(count("issue_summaries")).isZero();
        assertThat(count("idempotency_keys")).as("a refused publication leaves no key behind").isEqualTo(1);
        assertThat(issue(ADMIN_A, issueId)).containsEntry("version", 1).containsEntry("summary", null);
    }

    @Test
    void publicationNeedsTheVersionItWasBasedOn() {
        String issueId = sharedIssue();

        Response missing = publish(ADMIN_A, issueId, null, UUID.randomUUID().toString(), SUMMARY);
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.json().get("errors")).isEqualTo(List.of(Map.of("field", "If-Match", "reason", "required")));

        Response stale = publish(ADMIN_A, issueId, 1, "A newer summary that must not replace the first one.");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.json()).containsEntry("code", "VERSION_CONFLICT").containsEntry("currentVersion", 2)
                .containsEntry("currentState", "received");
        assertThat(count("issue_summaries")).isEqualTo(1);
    }

    @Test
    void onlyAnAdministratorOfTheBuildingPublishes() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);

        // The reporter sees the issue, so the answer is 403; everyone else learns nothing.
        assertThat(publish(RESIDENT_A1, issueId, 1, SUMMARY).status()).isEqualTo(403);
        for (String sub : List.of(RESIDENT_A2, ADMIN_B, RESIDENT_B1, CONTRACTOR, REVOKED_ADMIN_A, STRANGER)) {
            assertThat(publish(sub, issueId, 1, SUMMARY).status()).as(sub).isEqualTo(404);
        }
        assertThat(count("issue_summaries")).isZero();

        // Once it is shared, the building's residents see it, and still may not change the summary.
        assertThat(publish(ADMIN_A, issueId, 1, SUMMARY).status()).isEqualTo(200);
        Response byResident = publish(RESIDENT_A2, issueId, 2, "A resident must not rewrite the summary.");
        assertThat(byResident.status()).isEqualTo(403);
        assertThat(byResident.json()).containsEntry("code", "FORBIDDEN");
        assertThat(publish(RESIDENT_B1, issueId, 2, SUMMARY).status()).isEqualTo(404);
        assertThat(count("issue_summaries")).isEqualTo(1);
    }

    @Test
    void aRepeatedPublicationWithTheSameKeyIsReplayedAndADifferentRequestIsRefused() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        String key = UUID.randomUUID().toString();

        Response first = publish(ADMIN_A, issueId, "\"1\"", key, SUMMARY);
        Response again = publish(ADMIN_A, issueId, "\"1\"", key, SUMMARY);

        assertThat(first.status()).isEqualTo(200);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json()).as("the same result, although the version has moved on").isEqualTo(first.json());
        assertThat(again.header("ETag")).isEqualTo("\"2\"");
        assertThat(count("issue_summaries")).isEqualTo(1);
        assertThat(issue(ADMIN_A, issueId)).containsEntry("version", 2);

        Response other = publish(ADMIN_A, issueId, "\"1\"", key, "Another text sent with the same key.");
        assertThat(other.status()).isEqualTo(422);
        assertThat(other.json()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
    }

    @RepeatedTest(3)
    void twoAdministratorsPublishingFromTheSameVersionCannotBothWin() throws Exception {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Response>> results = new ArrayList<>();
            for (String text : List.of("First administrator's summary text.", "Second administrator's summary text.")) {
                Callable<Response> call = () -> {
                    start.await();
                    return publish(ADMIN_A, issueId, 1, text);
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Response> result : results) {
                statuses.add(result.get().status());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("issue_summaries")).isEqualTo(1);
        assertThat(issue(ADMIN_A, issueId)).containsEntry("version", 2);
    }

    // ---- Who sees a shared issue ------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void aPublishedSummaryMakesTheIssueVisibleToTheBuildingAndToNobodyElse() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        assertThat(triage(ADMIN_A, issueId, 1, "plumbing", "high", "InternalMarker: reported by the resident in 2B")
                .status()).isEqualTo(200);

        // Not shared yet: a neighbour who did not report it cannot see it, in any way.
        assertThat(api.get("/issues/" + issueId, tokens.token(RESIDENT_A2)).status()).isEqualTo(404);
        assertThat(api.get("/issues/" + issueId + "/history", tokens.token(RESIDENT_A2)).status()).isEqualTo(404);
        assertThat((List<Object>) api.get("/issues", tokens.token(RESIDENT_A2)).json().get("items")).isEmpty();
        // The reporter and the administrator see it without a summary, and without an impact count.
        assertThat(issue(RESIDENT_A1, issueId)).containsEntry("summary", null).containsEntry("impact", null)
                .containsEntry("subscribed", false);

        assertThat(publish(ADMIN_A, issueId, 2, SUMMARY).status()).isEqualTo(200);

        Response forNeighbour = api.get("/issues/" + issueId, tokens.token(RESIDENT_A2));
        assertThat(forNeighbour.status()).isEqualTo(200);
        Map<String, Object> shared = forNeighbour.json();
        assertThat(shared).containsEntry("state", "triaged").containsEntry("version", 3)
                .containsEntry("subscribed", false).containsEntry("suggestion", null);
        assertThat((Map<String, Object>) shared.get("summary")).containsEntry("text", SUMMARY);
        assertThat(shared.get("impact")).isEqualTo(Map.of("count", 0, "mine", false));
        assertThat(IssueTriageIT.triageOf(shared)).containsEntry("category", "plumbing").containsEntry("reason", null);
        // Never the report text, the reason or who reported.
        assertThat(forNeighbour.body()).doesNotContain("dripping").doesNotContain("InternalMarker")
                .doesNotContain(RESIDENT_A1);

        // Public history only, and the list now holds the issue.
        Response history = api.get("/issues/" + issueId + "/history", tokens.token(RESIDENT_A2));
        assertThat(history.status()).isEqualTo(200);
        assertThat((List<Map<String, Object>>) history.json().get("items")).extracting(e -> e.get("type"))
                .containsExactly("report_received", "triage_decided", "summary_published");
        assertThat(history.body()).doesNotContain("InternalMarker");
        List<Map<String, Object>> listed =
                (List<Map<String, Object>>) api.get("/issues", tokens.token(RESIDENT_A2)).json().get("items");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0)).containsEntry("issueId", issueId);
        assertThat((Map<String, Object>) listed.get(0).get("summary")).containsEntry("text", SUMMARY);

        // Another building, a contractor, a revoked member and a stranger still see nothing.
        for (String sub : List.of(RESIDENT_B1, ADMIN_B, CONTRACTOR, REVOKED_ADMIN_A, STRANGER)) {
            assertThat(api.get("/issues/" + issueId, tokens.token(sub)).status()).as(sub).isEqualTo(404);
            assertThat((List<Object>) api.get("/issues", tokens.token(sub)).json().get("items")).as(sub).isEmpty();
        }
    }

    @Test
    void aResidentWhoSeesASharedIssueStillMayNotTriageIt() {
        String issueId = sharedIssue();

        Response response = triage(RESIDENT_A2, issueId, 2, "plumbing", "high", "A resident must not decide");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json()).containsEntry("code", "FORBIDDEN");
    }

    // ---- Impact -----------------------------------------------------------------------------------------------

    @Test
    void residentsConfirmAndWithdrawTheirOwnImpactAndRepeatingIsSafe() {
        String issueId = sharedIssue();

        Response first = impact("PUT", RESIDENT_A2, issueId);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json()).isEqualTo(Map.of("count", 1, "mine", true));
        assertThat(impact("PUT", RESIDENT_A2, issueId).json()).as("repeated").isEqualTo(Map.of("count", 1, "mine", true));

        // The reporter may confirm too; reporting did not count by itself.
        assertThat(impact("PUT", RESIDENT_A1, issueId).json()).isEqualTo(Map.of("count", 2, "mine", true));
        assertThat(count("impact_confirmations")).isEqualTo(2);

        // Each caller sees the count and only their own choice; the administrator sees the count.
        assertThat(issue(RESIDENT_A2, issueId).get("impact")).isEqualTo(Map.of("count", 2, "mine", true));
        assertThat(issue(ADMIN_A, issueId).get("impact")).isEqualTo(Map.of("count", 2, "mine", false));

        Response withdrawn = impact("DELETE", RESIDENT_A2, issueId);
        assertThat(withdrawn.status()).isEqualTo(200);
        assertThat(withdrawn.json()).isEqualTo(Map.of("count", 1, "mine", false));
        assertThat(impact("DELETE", RESIDENT_A2, issueId).json()).as("repeated")
                .isEqualTo(Map.of("count", 1, "mine", false));
        assertThat(issue(RESIDENT_A2, issueId).get("impact")).isEqualTo(Map.of("count", 1, "mine", false));
        assertThat(issue(RESIDENT_A1, issueId).get("impact")).as("nobody removes another person's confirmation")
                .isEqualTo(Map.of("count", 1, "mine", true));
    }

    @Test
    void impactNeverChangesTheIssueAndSaysNothingAboutWho() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        triage(ADMIN_A, issueId, 1, "plumbing", "low", "Looks minor");
        publish(ADMIN_A, issueId, 2, SUMMARY);
        jdbc.sql("DELETE FROM outbox").update();
        long historyBefore = count("issue_history");

        Response confirmed = impact("PUT", RESIDENT_A2, issueId);
        impact("PUT", RESIDENT_A1, issueId);

        // Same version, state, category and urgency; no event and no history entry.
        Map<String, Object> issue = jdbc.sql("SELECT state, version, category, urgency FROM issues WHERE id = :id")
                .param("id", issueId).query().singleRow();
        assertThat(issue).containsEntry("state", "triaged").containsEntry("version", 3L)
                .containsEntry("category", "plumbing").containsEntry("urgency", "low");
        assertThat(count("outbox")).isZero();
        assertThat(count("issue_history")).isEqualTo(historyBefore);
        // A count only.
        assertThat(confirmed.body()).doesNotContain(RESIDENT_A2);
        assertThat(api.get("/issues/" + issueId, tokens.token(ADMIN_A)).body()).doesNotContain(RESIDENT_A2)
                .doesNotContain(RESIDENT_A1);
    }

    @Test
    void onlyResidentsOfTheBuildingConfirmImpactAndOnlyOnASharedIssue() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);

        // Not shared yet: the reporter is told so; a neighbour learns nothing.
        Response early = impact("PUT", RESIDENT_A1, issueId);
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.json()).containsEntry("code", "NOT_PUBLISHED");
        assertThat(impact("PUT", RESIDENT_A2, issueId).status()).isEqualTo(404);

        publish(ADMIN_A, issueId, 1, SUMMARY);

        // Administrators see the count; they do not contribute to it.
        for (String method : List.of("PUT", "DELETE")) {
            Response byAdmin = impact(method, ADMIN_A, issueId);
            assertThat(byAdmin.status()).isEqualTo(403);
            assertThat(byAdmin.json()).containsEntry("code", "FORBIDDEN");
            for (String sub : List.of(RESIDENT_B1, ADMIN_B, CONTRACTOR, REVOKED_ADMIN_A, STRANGER)) {
                assertThat(impact(method, sub, issueId).status()).as(method + " " + sub).isEqualTo(404);
            }
        }
        assertThat(impact("PUT", null, issueId).status()).isEqualTo(401);
        assertThat(count("impact_confirmations")).isZero();
    }

    @RepeatedTest(3)
    void concurrentConfirmationsByTheSamePersonCountOnce() throws Exception {
        String issueId = sharedIssue();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Response>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Callable<Response> call = () -> {
                    start.await();
                    return impact("PUT", RESIDENT_A2, issueId);
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            for (Future<Response> result : results) {
                assertThat(result.get().status()).isEqualTo(200);
                assertThat(result.get().json()).isEqualTo(Map.of("count", 1, "mine", true));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("impact_confirmations")).isEqualTo(1);
    }

    // ---- Subscription -----------------------------------------------------------------------------------------

    @Test
    void aSubscriptionIsTheCallersOwnChoiceSeparateFromImpact() {
        String issueId = sharedIssue();

        // Off by default, and confirming impact does not turn it on.
        impact("PUT", RESIDENT_A2, issueId);
        assertThat(issue(RESIDENT_A2, issueId)).containsEntry("subscribed", false);

        Response on = subscription("PUT", RESIDENT_A2, issueId);
        assertThat(on.status()).isEqualTo(200);
        assertThat(on.json()).isEqualTo(Map.of("subscribed", true));
        assertThat(subscription("PUT", RESIDENT_A2, issueId).json()).as("repeated").isEqualTo(Map.of("subscribed", true));
        assertThat(count("issue_subscriptions")).isEqualTo(1);
        assertThat(issue(RESIDENT_A2, issueId)).containsEntry("subscribed", true);
        assertThat(issue(RESIDENT_A1, issueId)).as("somebody else's choice").containsEntry("subscribed", false);

        // Turning it off keeps the impact confirmation and the access.
        Response off = subscription("DELETE", RESIDENT_A2, issueId);
        assertThat(off.json()).isEqualTo(Map.of("subscribed", false));
        assertThat(subscription("DELETE", RESIDENT_A2, issueId).json()).as("repeated")
                .isEqualTo(Map.of("subscribed", false));
        Map<String, Object> after = issue(RESIDENT_A2, issueId);
        assertThat(after).containsEntry("subscribed", false);
        assertThat(after.get("impact")).isEqualTo(Map.of("count", 1, "mine", true));

        // And withdrawing impact leaves a subscription alone.
        subscription("PUT", RESIDENT_A2, issueId);
        impact("DELETE", RESIDENT_A2, issueId);
        assertThat(issue(RESIDENT_A2, issueId)).containsEntry("subscribed", true);
    }

    @Test
    void anyoneWhoSeesTheIssueMaySubscribeAndNobodyElse() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);

        // Before it is shared: the reporter and the administrator, not a neighbour.
        assertThat(subscription("PUT", RESIDENT_A1, issueId).json()).isEqualTo(Map.of("subscribed", true));
        assertThat(subscription("PUT", ADMIN_A, issueId).json()).isEqualTo(Map.of("subscribed", true));
        assertThat(subscription("PUT", RESIDENT_A2, issueId).status()).isEqualTo(404);

        publish(ADMIN_A, issueId, 1, SUMMARY);

        assertThat(subscription("PUT", RESIDENT_A2, issueId).status()).isEqualTo(200);
        for (String method : List.of("PUT", "DELETE")) {
            for (String sub : List.of(RESIDENT_B1, ADMIN_B, CONTRACTOR, REVOKED_ADMIN_A, STRANGER)) {
                assertThat(subscription(method, sub, issueId).status()).as(method + " " + sub).isEqualTo(404);
            }
        }
        assertThat(subscription("PUT", null, issueId).status()).isEqualTo(401);
        // Neither changes the issue.
        assertThat(issue(ADMIN_A, issueId)).containsEntry("version", 2);
        assertThat(count("issue_subscriptions")).isEqualTo(3);
    }

    // ---- Cross-site requests and unknown issues ---------------------------------------------------------------

    @Test
    void everyChangeNeedsTheCsrfHeader() {
        String issueId = sharedIssue();
        var crossSite = api.withoutCsrfHeader();

        assertThat(crossSite.postWithHeaders("/issues/" + issueId + "/publication", tokens.token(ADMIN_A),
                Map.of("If-Match", "\"2\"", "Idempotency-Key", UUID.randomUUID().toString()),
                Map.of("summary", SUMMARY)).status()).isEqualTo(403);
        for (String method : List.of("PUT", "DELETE")) {
            assertThat(crossSite.method(method, "/issues/" + issueId + "/impact", tokens.token(RESIDENT_A2), null)
                    .status()).isEqualTo(403);
            assertThat(crossSite.method(method, "/issues/" + issueId + "/subscription", tokens.token(RESIDENT_A2), null)
                    .status()).isEqualTo(403);
        }
        assertThat(count("impact_confirmations")).isZero();
        assertThat(count("issue_subscriptions")).isZero();
        assertThat(count("issue_summaries")).isEqualTo(1);
    }

    @Test
    void anUnknownIssueIsNotFoundForEveryOperation() {
        String unknown = UUID.randomUUID().toString();

        assertThat(publish(ADMIN_A, unknown, 1, SUMMARY).status()).isEqualTo(404);
        assertThat(publish(ADMIN_A, "not-a-uuid", 1, SUMMARY).status()).isEqualTo(404);
        assertThat(impact("PUT", RESIDENT_A1, unknown).status()).isEqualTo(404);
        assertThat(subscription("PUT", RESIDENT_A1, unknown).status()).isEqualTo(404);
        assertThat(count("idempotency_keys")).isZero();
    }
}
