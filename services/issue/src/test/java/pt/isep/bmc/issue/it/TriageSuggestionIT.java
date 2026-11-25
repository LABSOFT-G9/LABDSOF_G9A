package pt.isep.bmc.issue.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.issue.support.Fixtures.*;

import java.util.ArrayList;
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
import org.springframework.beans.factory.annotation.Autowired;
import pt.isep.bmc.issue.application.command.RecordTriageSuggestionCommand;
import pt.isep.bmc.issue.application.command.SuggestionResult;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.application.port.in.RecordTriageSuggestionUseCase;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.SuggestionInput;
import pt.isep.bmc.issue.domain.shared.ValidationException;
import pt.isep.bmc.issue.support.Api.Response;
import pt.isep.bmc.issue.support.IntegrationTestSupport;
import tools.jackson.databind.json.JsonMapper;

/**
 * US-03-03 on real MySQL: a {@code TriageSuggested} suggestion is recorded by
 * issue version, once per event, and never competes with a person's decision
 * (technical design 3.1, 3.3 and 4.6). The suggestion enters through the use
 * case the queue consumer will call; the consumer itself comes with EN-02-02.
 */
class TriageSuggestionIT extends IntegrationTestSupport {

    static final String RATIONALE = "The report mentions water under a sink.";
    static final String CORRELATION = "corr-suggestion-it";

    @Autowired
    RecordTriageSuggestionUseCase suggestions;

    @Test
    void aSuggestionMovesTheIssueToInTriageAndItsAdministratorsSeeItAsUnconfirmed() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);

        assertThat(record(issueId, ai(UUID.randomUUID(), 1))).isEqualTo(SuggestionResult.APPLIED);

        Response read = api.get("/issues/" + issueId, tokens.token(ADMIN_A));
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.header("ETag")).isEqualTo("\"2\"");
        assertThat(read.json()).containsEntry("state", "in_triage").containsEntry("version", 2)
                .containsEntry("triage", null);
        Map<String, Object> suggestion = suggestionOf(read.json());
        assertThat(suggestion).containsEntry("category", "plumbing").containsEntry("urgency", "high")
                .containsEntry("origin", "ai").containsEntry("rationale", RATIONALE)
                .containsEntry("needsReview", true).containsEntry("failureCode", null)
                .containsEntry("rulesVersion", null).containsEntry("modelVersion", "model-x-1");
        assertThat((String) suggestion.get("suggestedAt")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");

        // Nothing is decided: the issue itself still has no category or urgency.
        Map<String, Object> issue = jdbc.sql("SELECT state, version, category, urgency FROM issues WHERE id = :id")
                .param("id", issueId).query().singleRow();
        assertThat(issue).containsEntry("state", "in_triage").containsEntry("version", 2L)
                .containsEntry("category", null).containsEntry("urgency", null);
    }

    @Test
    void whoeverDoesNotAdministerTheBuildingNeverSeesTheSuggestion() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        record(issueId, ai(UUID.randomUUID(), 1));

        // The reporter follows the issue, and sees its state only.
        Response reporter = api.get("/issues/" + issueId, tokens.token(RESIDENT_A1));
        assertThat(reporter.status()).isEqualTo(200);
        assertThat(reporter.json()).containsEntry("state", "in_triage").containsEntry("suggestion", null);
        assertThat(reporter.body()).doesNotContain(RATIONALE, "model-x-1");
        assertThat(itemsOf(api.get("/issues", tokens.token(RESIDENT_A1)).json()))
                .singleElement().satisfies(item -> assertThat(item).containsEntry("suggestion", null));
        assertThat(itemsOf(api.get("/issues/" + issueId + "/history", tokens.token(RESIDENT_A1)).json()))
                .extracting(entry -> entry.get("type")).containsExactly("report_received");

        // Once shared, other residents of the building see the issue, and still no suggestion.
        assertThat(publish(ADMIN_A, issueId, 2, "A leak in the garage is being looked at.").status()).isEqualTo(200);
        Response neighbour = api.get("/issues/" + issueId, tokens.token(RESIDENT_A2));
        assertThat(neighbour.status()).isEqualTo(200);
        assertThat(neighbour.json()).containsEntry("suggestion", null);
        assertThat(itemsOf(api.get("/issues", tokens.token(RESIDENT_A2)).json()))
                .singleElement().satisfies(item -> assertThat(item).containsEntry("suggestion", null));

        assertThat(api.get("/issues/" + issueId, tokens.token(ADMIN_B)).status()).isEqualTo(404);
        assertThat(api.get("/issues/" + issueId, tokens.token(CONTRACTOR)).status()).isEqualTo(404);
    }

    @Test
    void theListCarriesTheSuggestionWithoutItsRationale() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        record(issueId, ai(UUID.randomUUID(), 1));

        Response list = api.get("/issues", tokens.token(ADMIN_A));

        assertThat(list.status()).isEqualTo(200);
        assertThat(itemsOf(list.json())).singleElement().satisfies(item -> assertThat(suggestionOf(item))
                .containsEntry("category", "plumbing").containsEntry("urgency", "high").containsEntry("origin", "ai")
                .containsEntry("needsReview", true).containsEntry("modelVersion", "model-x-1")
                .containsEntry("rationale", null));
        assertThat(list.body()).doesNotContain(RATIONALE);
    }

    @Test
    void aFallbackSuggestionSaysWhyTheAiResultWasNotUsed() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        SuggestionInput fallback = new SuggestionInput(UUID.randomUUID(), 1, "electrical", "normal", RATIONALE, false,
                "fallback", null, null, "rules-v1", null, "timeout");

        assertThat(record(issueId, fallback)).isEqualTo(SuggestionResult.APPLIED);

        assertThat(suggestionOf(api.get("/issues/" + issueId, tokens.token(ADMIN_A)).json()))
                .containsEntry("origin", "fallback").containsEntry("failureCode", "timeout")
                .containsEntry("rulesVersion", "rules-v1").containsEntry("modelVersion", null)
                .containsEntry("needsReview", false);
    }

    @Test
    void theHistoryShowsEverySuggestionToAdministratorsWithItsOriginAndWhetherItWasApplied() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        record(issueId, ai(UUID.randomUUID(), 1));
        // The AI service keeps no state: a redelivered request is classified again, as a new event.
        SuggestionInput again = new SuggestionInput(UUID.randomUUID(), 1, "other", "low", RATIONALE, false, "rules",
                null, null, "rules-v1", null, null);
        assertThat(record(issueId, again)).isEqualTo(SuggestionResult.RECORDED_LATE);

        Response history = api.get("/issues/" + issueId + "/history", tokens.token(ADMIN_A));

        assertThat(history.status()).isEqualTo(200);
        List<Map<String, Object>> items = itemsOf(history.json());
        assertThat(items).extracting(e -> e.get("sequence"), e -> e.get("type"), e -> e.get("visibility"),
                        e -> e.get("actorRole"), e -> e.get("category"), e -> e.get("urgency"), e -> e.get("origin"),
                        e -> e.get("applied"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "report_received", "public", "resident", null, null, null, null),
                        org.assertj.core.groups.Tuple.tuple(2, "triage_suggested", "internal", "system", "plumbing", "high", "ai", true),
                        org.assertj.core.groups.Tuple.tuple(3, "triage_suggested", "internal", "system", "other", "low", "rules", false));
        // The explanation is written from the report: it is on the issue, never in the history.
        assertThat(history.body()).doesNotContain(RATIONALE);

        // The first suggestion stays the one shown; the second changed nothing.
        Response read = api.get("/issues/" + issueId, tokens.token(ADMIN_A));
        assertThat(read.json()).containsEntry("state", "in_triage").containsEntry("version", 2);
        assertThat(suggestionOf(read.json())).containsEntry("category", "plumbing").containsEntry("origin", "ai");
    }

    @Test
    void theStateChangeIsWrittenWithTheSuggestionAndCarriesNoSuggestedValue() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        jdbc.sql("DELETE FROM outbox").update();

        record(issueId, ai(UUID.randomUUID(), 1));

        Map<String, Object> row = jdbc.sql("""
                SELECT event_type, routing_key, aggregate_id, aggregate_version, building_id, correlation_id, status, payload
                  FROM outbox
                """).query().singleRow();
        assertThat(row).containsEntry("event_type", "IssueStateChanged")
                .containsEntry("routing_key", "issue.IssueStateChanged").containsEntry("aggregate_id", issueId)
                .containsEntry("aggregate_version", 2L).containsEntry("building_id", BUILDING_A)
                .containsEntry("correlation_id", CORRELATION).containsEntry("status", "pending");
        Map<String, Object> data = dataOf(row);
        assertThat(data).containsEntry("issueId", issueId).containsEntry("state", "in_triage")
                .containsEntry("version", 2).containsEntry("dispatchable", false)
                .containsEntry("category", null).containsEntry("urgency", null);
        assertThat(row.get("payload").toString()).doesNotContain(RATIONALE, "plumbing", "model-x-1");
    }

    @Test
    void aDecisionBasedOnTheVersionBeforeTheSuggestionIsAConflictAndDecidingHidesTheSuggestion() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        record(issueId, ai(UUID.randomUUID(), 1));

        Response stale = triage(ADMIN_A, issueId, 1, "electrical", "critical", "Sparks at the socket");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.json()).containsEntry("code", "VERSION_CONFLICT");

        Response decided = triage(ADMIN_A, issueId, 2, "electrical", "critical", "Sparks at the socket");
        assertThat(decided.status()).isEqualTo(200);
        assertThat(decided.json()).containsEntry("state", "triaged").containsEntry("version", 3)
                .containsEntry("category", "electrical").containsEntry("urgency", "critical");

        // The person's decision is what the issue shows; the suggestion stays in the history only.
        Response read = api.get("/issues/" + issueId, tokens.token(ADMIN_A));
        assertThat(read.json()).containsEntry("state", "triaged").containsEntry("suggestion", null);
        assertThat(itemsOf(api.get("/issues", tokens.token(ADMIN_A)).json()))
                .singleElement().satisfies(item -> assertThat(item).containsEntry("suggestion", null));
        assertThat(itemsOf(api.get("/issues/" + issueId + "/history", tokens.token(ADMIN_A)).json()))
                .extracting(e -> e.get("type")).containsExactly("report_received", "triage_suggested", "triage_decided");
        assertThat(count("triage_suggestions")).isEqualTo(1);
    }

    @Test
    void aSuggestionThatArrivesAfterTheDecisionIsKeptAndChangesNothing() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        assertThat(triage(ADMIN_A, issueId, 1, "electrical", "critical", "Sparks at the socket").status()).isEqualTo(200);
        jdbc.sql("DELETE FROM outbox").update();

        // Computed from version 1, which the decision already left.
        assertThat(record(issueId, ai(UUID.randomUUID(), 1))).isEqualTo(SuggestionResult.RECORDED_LATE);
        // And one that names the current version: a decided issue takes no suggestion either.
        assertThat(record(issueId, ai(UUID.randomUUID(), 2))).isEqualTo(SuggestionResult.RECORDED_LATE);

        Response read = api.get("/issues/" + issueId, tokens.token(ADMIN_A));
        assertThat(read.header("ETag")).isEqualTo("\"2\"");
        assertThat(read.json()).containsEntry("state", "triaged").containsEntry("version", 2)
                .containsEntry("suggestion", null);
        assertThat(IssueTriageIT.triageOf(read.json())).containsEntry("category", "electrical")
                .containsEntry("urgency", "critical").containsEntry("decisionSource", "human");
        assertThat(count("outbox")).isZero();
        assertThat(count("triage_decisions")).isEqualTo(1);

        List<Map<String, Object>> items = itemsOf(api.get("/issues/" + issueId + "/history", tokens.token(ADMIN_A)).json());
        assertThat(items).extracting(e -> e.get("type"), e -> e.get("applied")).containsExactly(
                org.assertj.core.groups.Tuple.tuple("report_received", null),
                org.assertj.core.groups.Tuple.tuple("triage_decided", null),
                org.assertj.core.groups.Tuple.tuple("triage_suggested", false),
                org.assertj.core.groups.Tuple.tuple("triage_suggested", false));
        // The decision's own values are untouched by the suggested ones next to it.
        assertThat(items.get(1)).containsEntry("category", "electrical").containsEntry("urgency", "critical");
        assertThat(items.get(2)).containsEntry("category", "plumbing").containsEntry("urgency", "high");
    }

    @Test
    void aSuggestionNeverTouchesTheCriticalCheck() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        String before = criticalCheck(issueId);
        SuggestionInput low = new SuggestionInput(UUID.randomUUID(), 1, "other", "low", RATIONALE, false, "ai",
                "model-x-1", "triage-v1", null, "triage-output-v1", null);

        record(issueId, low);
        record(issueId, ai(UUID.randomUUID(), 1));

        assertThat(criticalCheck(issueId)).isEqualTo(before);
    }

    @Test
    void aSummaryPublishedBeforeTheSuggestionMakesItLate() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        assertThat(publish(ADMIN_A, issueId, 1, "A leak in the garage is being looked at.").status()).isEqualTo(200);

        // The request was written at version 1; publishing moved the issue to version 2.
        assertThat(record(issueId, ai(UUID.randomUUID(), 1))).isEqualTo(SuggestionResult.RECORDED_LATE);

        Response read = api.get("/issues/" + issueId, tokens.token(ADMIN_A));
        assertThat(read.json()).containsEntry("state", "received").containsEntry("version", 2)
                .containsEntry("suggestion", null);
    }

    @Test
    void theSameEventDeliveredAgainIsRecordedOnce() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        jdbc.sql("DELETE FROM outbox").update();
        SuggestionInput event = ai(UUID.randomUUID(), 1);

        assertThat(record(issueId, event)).isEqualTo(SuggestionResult.APPLIED);
        assertThat(record(issueId, event)).isEqualTo(SuggestionResult.DUPLICATE);
        assertThat(record(issueId, event)).isEqualTo(SuggestionResult.DUPLICATE);

        assertOneSuggestion(issueId);
    }

    @RepeatedTest(3)
    void theSameEventDeliveredSeveralTimesAtOnceIsRecordedOnce() throws Exception {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        jdbc.sql("DELETE FROM outbox").update();
        SuggestionInput event = ai(UUID.randomUUID(), 1);
        List<Callable<SuggestionResult>> deliveries = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            deliveries.add(() -> record(issueId, event));
        }

        List<SuggestionResult> results = runTogether(deliveries);

        assertThat(results).filteredOn(SuggestionResult.APPLIED::equals).hasSize(1);
        assertThat(results).filteredOn(SuggestionResult.DUPLICATE::equals).hasSize(5);
        assertOneSuggestion(issueId);
    }

    @RepeatedTest(5)
    void aSuggestionAndADecisionBasedOnTheSameVersionNeverBothApply() throws Exception {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        List<Callable<Object>> calls = List.of(
                () -> record(issueId, ai(UUID.randomUUID(), 1)),
                () -> triage(ADMIN_A, issueId, 1, "electrical", "critical", "Sparks at the socket"));

        List<Object> results = runTogether(calls);

        SuggestionResult suggested = (SuggestionResult) results.get(0);
        int decided = ((Response) results.get(1)).status();
        Map<String, Object> issue = jdbc.sql("SELECT state, version, category FROM issues WHERE id = :id")
                .param("id", issueId).query().singleRow();
        if (suggested == SuggestionResult.APPLIED) {
            // The suggestion took version 1 first: the decision was based on a version that is gone.
            assertThat(decided).isEqualTo(409);
            assertThat(issue).containsEntry("state", "in_triage").containsEntry("version", 2L)
                    .containsEntry("category", null);
        } else {
            // The decision took version 1 first: the suggestion is late and changes nothing.
            assertThat(suggested).isEqualTo(SuggestionResult.RECORDED_LATE);
            assertThat(decided).isEqualTo(200);
            assertThat(issue).containsEntry("state", "triaged").containsEntry("version", 2L)
                    .containsEntry("category", "electrical");
        }
        assertThat(count("triage_suggestions")).isEqualTo(1);
        // One history entry per version step, plus the suggestion's own entry when it was late.
        assertThat(jdbc.sql("SELECT sequence FROM issue_history WHERE issue_id = :id ORDER BY sequence")
                .param("id", issueId).query(Integer.class).list())
                .isEqualTo(suggested == SuggestionResult.APPLIED ? List.of(1, 2) : List.of(1, 2, 3));
    }

    @Test
    void aSuggestionThatBreaksTheContractOrNamesNoIssueStoresNothing() {
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        jdbc.sql("DELETE FROM outbox").update();
        SuggestionInput unknownCategory = new SuggestionInput(UUID.randomUUID(), 1, "gardening", "high", RATIONALE,
                false, "ai", null, null, null, null, null);
        SuggestionInput noRationale = new SuggestionInput(UUID.randomUUID(), 1, "plumbing", "high", " ", false, "ai",
                null, null, null, null, null);

        assertThatThrownBy(() -> record(issueId, unknownCategory)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> record(issueId, noRationale)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> record(UUID.randomUUID().toString(), ai(UUID.randomUUID(), 1)))
                .isInstanceOf(NotFoundException.class);

        assertThat(count("triage_suggestions")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(count("issue_history")).isEqualTo(1);
        Response read = api.get("/issues/" + issueId, tokens.token(ADMIN_A));
        assertThat(read.json()).containsEntry("state", "received").containsEntry("version", 1)
                .containsEntry("suggestion", null);
    }

    private void assertOneSuggestion(String issueId) {
        assertThat(count("triage_suggestions")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(count("issue_history")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT version FROM issues WHERE id = :id").param("id", issueId).query(Long.class).single())
                .isEqualTo(2L);
    }

    private String criticalCheck(String issueId) {
        return jdbc.sql("SELECT critical_check_status FROM issues WHERE id = :id").param("id", issueId)
                .query(String.class).single();
    }

    private SuggestionResult record(String issueId, SuggestionInput suggestion) {
        return suggestions.record(new RecordTriageSuggestionCommand(
                new IssueId(UUID.fromString(issueId)), suggestion, CORRELATION));
    }

    static SuggestionInput ai(UUID eventId, long sourceVersion) {
        return new SuggestionInput(eventId, sourceVersion, "plumbing", "high", RATIONALE, true, "ai", "model-x-1",
                "triage-v1", null, "triage-output-v1", null);
    }

    private static <T> List<T> runTogether(List<Callable<T>> calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> suggestionOf(Map<String, Object> issue) {
        return (Map<String, Object>) issue.get("suggestion");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> itemsOf(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Map<String, Object> outboxRow) {
        Map<String, Object> envelope = JsonMapper.builder().build().readValue(outboxRow.get("payload").toString(), Map.class);
        return (Map<String, Object>) envelope.get("data");
    }
}
