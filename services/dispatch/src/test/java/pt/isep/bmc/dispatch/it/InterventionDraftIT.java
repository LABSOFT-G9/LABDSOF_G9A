package pt.isep.bmc.dispatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.support.Fixtures.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.support.Api;
import pt.isep.bmc.dispatch.support.IntegrationTestSupport;

/** US-06: {@code POST /interventions} saves a draft request and sends nothing. */
class InterventionDraftIT extends IntegrationTestSupport {

    @Test
    void anAdministratorSavesADraftAwaitingApproval() {
        String issueId = triagedPlumbingIssue();

        var response = draft(ADMIN_A, issueId, PLUMBING_CO);

        assertThat(response.status()).isEqualTo(201);
        String interventionId = (String) response.json().get("interventionId");
        assertThat(response.json()).containsEntry("state", "awaiting_approval").containsEntry("version", 1)
                .containsEntry("proposalVersion", 1).containsEntry("contractorId", PLUMBING_CO);
        assertThat(response.header("ETag")).isEqualTo("\"1\"");
        assertThat(response.header("Location")).isEqualTo("/interventions/" + interventionId);

        Map<String, Object> stored = jdbc.sql("""
                        SELECT i.issue_id, i.building_id, i.projected_issue_version, i.created_by,
                               p.author_role, p.scope_extract, p.terms, p.window_start
                          FROM interventions i JOIN proposal_versions p ON p.intervention_id = i.id
                         WHERE i.id = :id
                        """).param("id", interventionId).query().singleRow();
        assertThat(stored).containsEntry("issue_id", issueId).containsEntry("building_id", BUILDING_A)
                .containsEntry("projected_issue_version", 3L).containsEntry("created_by", ADMIN_A)
                .containsEntry("author_role", "administrator").containsEntry("scope_extract", SCOPE)
                .containsEntry("terms", TERMS);

        var reloaded = api.get("/interventions/" + interventionId, tokens.token(ADMIN_A));
        assertThat(reloaded.status()).isEqualTo(200);
        assertThat(reloaded.header("ETag")).isEqualTo("\"1\"");
        assertThat(Api.map(reloaded.json().get("proposal"))).containsEntry("proposalVersion", 1)
                .containsEntry("window", Map.of("start", "2099-11-05T09:00:00Z", "end", "2099-11-05T12:00:00Z"));
        assertThat(Api.map(reloaded.json().get("contractor")))
                .isEqualTo(Map.of("contractorId", PLUMBING_CO, "displayName", "Plumbing Co.", "synthetic", true));
    }

    @Test
    void everyInvalidFieldIsReportedAndNothingIsStored() {
        String issueId = triagedPlumbingIssue();
        Map<String, Object> body = draftBody(issueId, "nope");
        body.put("scopeExtract", "short");
        body.put("terms", "   ");
        body.put("window", Map.of("start", "2020-01-01T09:00:00Z", "end", "2020-01-01T08:00:00Z"));

        var response = api.post("/interventions", tokens.token(ADMIN_A), UUID.randomUUID().toString(), body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json()).containsEntry("code", "VALIDATION_FAILED");
        assertThat(Api.list(response.json().get("errors"))).containsExactlyInAnyOrder(
                Map.of("field", "contractorId", "reason", "invalid_format"),
                Map.of("field", "scopeExtract", "reason", "too_short"),
                Map.of("field", "terms", "reason", "required"),
                Map.of("field", "window.start", "reason", "not_in_future"),
                Map.of("field", "window.end", "reason", "not_after_start"));
        assertThat(count("interventions")).isZero();
        assertThat(count("idempotency_keys")).as("a refused request leaves no key").isZero();
    }

    @Test
    void anUnknownOrIneligibleContractorIsRefusedTheSameWay() {
        String issueId = triagedPlumbingIssue();
        for (String contractor : List.of(UUID.randomUUID().toString(), INACTIVE_PLUMBING, PLUMBING_B_ONLY, ELECTRICAL)) {
            var response = draft(ADMIN_A, issueId, contractor);
            assertThat(response.status()).as(contractor).isEqualTo(400);
            assertThat(response.json().get("errors"))
                    .isEqualTo(List.of(Map.of("field", "contractorId", "reason", "not_eligible")));
        }
        assertThat(count("interventions")).isZero();
    }

    @Test
    void anIssueThatIsNotReadyIsRefusedWithItsState() {
        String issueId = UUID.randomUUID().toString();
        events.issueStateChanged(issueId, BUILDING_A, "in_triage", 2, false, "plumbing");

        var response = draft(ADMIN_A, issueId, PLUMBING_CO);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json()).containsEntry("code", "INVALID_TRANSITION").containsEntry("currentState", "in_triage");
        assertThat(count("interventions")).isZero();
    }

    @Test
    void aSecondActiveInterventionForTheSameIssueIsRefusedAndNamesTheFirst() {
        String issueId = triagedPlumbingIssue();
        String first = (String) draft(ADMIN_A, issueId, PLUMBING_CO).json().get("interventionId");

        var second = draft(ADMIN_A, issueId, AQUA_PLUMBERS);

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.json()).containsEntry("code", "INVALID_TRANSITION").containsEntry("activeInterventionId", first);
        assertThat(count("interventions")).isEqualTo(1);
    }

    @Test
    void residentsContractorsOtherBuildingsAdministratorsAndUnknownIssuesGet404AndNothingIsStored() {
        String issueId = triagedPlumbingIssue();
        for (String sub : List.of(RESIDENT_A1, CONTRACTOR, ADMIN_B)) {
            var response = draft(sub, issueId, PLUMBING_CO);
            assertThat(response.status()).as(sub).isEqualTo(404);
        }
        assertThat(draft(ADMIN_A, UUID.randomUUID().toString(), PLUMBING_CO).status()).isEqualTo(404);
        assertThat(count("interventions")).isZero();
    }

    @Test
    void repeatingTheSameSaveReturnsTheSameDraftOnce() {
        String issueId = triagedPlumbingIssue();
        String key = UUID.randomUUID().toString();

        var first = api.post("/interventions", tokens.token(ADMIN_A), key, draftBody(issueId, PLUMBING_CO));
        var again = api.post("/interventions", tokens.token(ADMIN_A), key, draftBody(issueId, PLUMBING_CO));

        assertThat(first.status()).isEqualTo(201);
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.json()).isEqualTo(first.json());
        assertThat(count("interventions")).isEqualTo(1);

        var changed = api.post("/interventions", tokens.token(ADMIN_A), key, draftBody(issueId, AQUA_PLUMBERS));
        assertThat(changed.status()).isEqualTo(422);
        assertThat(changed.json()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void aReplayIsAnsweredByTodaysPermissions() {
        String issueId = triagedPlumbingIssue();
        String key = UUID.randomUUID().toString();
        assertThat(api.post("/interventions", tokens.token(ADMIN_A), key, draftBody(issueId, PLUMBING_CO)).status())
                .isEqualTo(201);

        events.revoked(MEMBERSHIP_ADMIN_A, ADMIN_A, BUILDING_A, "administrator", 2);

        assertThat(api.post("/interventions", tokens.token(ADMIN_A), key, draftBody(issueId, PLUMBING_CO)).status())
                .isEqualTo(404);
    }

    @Test
    void theIdempotencyKeyIsRequired() {
        var response = api.post("/interventions", tokens.token(ADMIN_A), null,
                draftBody(triagedPlumbingIssue(), PLUMBING_CO));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("errors"))
                .isEqualTo(List.of(Map.of("field", "Idempotency-Key", "reason", "required")));
    }

    @Test
    void concurrentDraftsForOneIssueLeaveExactlyOneIntervention() throws Exception {
        String issueId = triagedPlumbingIssue();
        int attempts = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<Future<Api.Response>> results = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                Callable<Api.Response> call = () -> {
                    start.await();
                    return draft(ADMIN_A, issueId, PLUMBING_CO);
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Api.Response> result : results) {
                statuses.add(result.get().status());
            }
            assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 409).hasSize(attempts - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("interventions")).isEqualTo(1);
    }

    @Test
    void textFieldsMustBeJsonStrings() {
        Map<String, Object> body = draftBody(triagedPlumbingIssue(), PLUMBING_CO);
        body.put("terms", 42);
        assertThat(api.post("/interventions", tokens.token(ADMIN_A), UUID.randomUUID().toString(), body).status())
                .isEqualTo(400);
        assertThat(count("interventions")).isZero();
    }
}
