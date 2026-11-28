package pt.isep.bmc.dispatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.support.Fixtures.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.support.Api;
import pt.isep.bmc.dispatch.support.IntegrationTestSupport;

/** US-07: {@code POST /interventions/{id}/approval} sends the request once, with {@code InterventionApproved}. */
class ApprovalIT extends IntegrationTestSupport {

    private String issueId;
    private String interventionId;

    private void givenADraftFor(String contractorId) {
        issueId = triagedPlumbingIssue();
        interventionId = (String) draft(ADMIN_A, issueId, contractorId).json().get("interventionId");
    }

    private void assertNothingSent() {
        assertThat(jdbc.sql("SELECT state FROM interventions").query(String.class).single())
                .isEqualTo("awaiting_approval");
        assertThat(jdbc.sql("SELECT version FROM interventions").query(Long.class).single()).isEqualTo(1);
        assertThat(count("outbox")).isZero();
    }

    @Test
    void approvalSendsTheRequestAndWritesItsEventInTheOutbox() {
        givenADraftFor(PLUMBING_CO);

        var response = approve(ADMIN_A, interventionId, 1, 1);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("ETag")).isEqualTo("\"2\"");
        assertThat(response.json()).isEqualTo(Map.of("interventionId", interventionId, "state", "sent",
                "version", 2, "approvedProposalVersion", 1));

        Map<String, Object> event = outboxEvents().getFirst();
        assertThat(event).containsEntry("specversion", "1.0").containsEntry("type", "InterventionApproved")
                .containsEntry("source", "dispatch").containsEntry("subject", interventionId)
                .containsEntry("aggregateversion", 2).containsEntry("buildingid", BUILDING_A);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("issueId", issueId);
        data.put("contractorUserId", CONTRACTOR);
        data.put("contractorDisplayName", "Plumbing Co.");
        data.put("approvedProposalVersion", 1);
        data.put("interventionState", "sent");
        assertThat(event.get("data")).isEqualTo(data);
        assertThat(jdbc.sql("SELECT CONCAT(routing_key, '|', status, '|', aggregate_id) FROM outbox")
                .query(String.class).single()).isEqualTo("dispatch.InterventionApproved|pending|" + interventionId);
        assertThat(outboxEvents().toString()).as("no free text in events").doesNotContain(SCOPE, TERMS);

        var reloaded = api.get("/interventions/" + interventionId, tokens.token(ADMIN_A));
        assertThat(reloaded.json()).containsEntry("state", "sent").containsEntry("approvedProposalVersion", 1)
                .containsEntry("agreedProposalVersion", null);
        assertThat(Api.map(Api.list(reloaded.json().get("proposals")).getFirst()).get("approvedAt")).isNotNull();
    }

    @Test
    void repeatingTheApprovalSendsNothingAgain() {
        givenADraftFor(PLUMBING_CO);
        String key = UUID.randomUUID().toString();

        var first = approve(ADMIN_A, interventionId, 1, key, 1);
        var again = approve(ADMIN_A, interventionId, 1, key, 1);

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json()).isEqualTo(first.json());
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(approve(ADMIN_A, interventionId, 1, key, 2).status()).as("same key, other body").isEqualTo(422);
        assertThat(approve(ADMIN_A, interventionId, 2, 1).json()).as("a second approval")
                .containsEntry("code", "INVALID_TRANSITION").containsEntry("currentState", "sent");
    }

    @Test
    void twoApprovalsFromTheSameVersionSendOneRequest() throws Exception {
        givenADraftFor(PLUMBING_CO);
        CompletableFuture<Api.Response> first;
        CompletableFuture<Api.Response> second;
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement(
                    "SELECT issue_id FROM issue_projection WHERE issue_id = ? FOR UPDATE")) {
                lock.setString(1, issueId);
                lock.executeQuery();
            }
            first = CompletableFuture.supplyAsync(() -> approve(ADMIN_A, interventionId, 1, 1));
            second = CompletableFuture.supplyAsync(() -> approve(ADMIN_A, interventionId, 1, 1));
            Thread.sleep(2_000);
            blocker.commit();
        }

        List<Api.Response> responses = List.of(first.get(), second.get());

        assertThat(responses).extracting(Api.Response::status).containsExactlyInAnyOrder(200, 409);
        assertThat(count("outbox")).as("one request sent").isEqualTo(1);
    }

    @Test
    void aContractorWithoutALoginCannotReceiveTheRequest() {
        givenADraftFor(PLUMBING_CO_TWIN);

        var response = approve(ADMIN_A, interventionId, 1, 1);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json()).containsEntry("code", "CONTRACTOR_HAS_NO_ACCOUNT");
        assertNothingSent();
    }

    @Test
    void aContractorNoLongerEligibleCannotReceiveTheRequest() {
        givenADraftFor(PLUMBING_CO);
        jdbc.sql("UPDATE contractors SET active = FALSE WHERE id = ?").param(PLUMBING_CO).update();

        var response = approve(ADMIN_A, interventionId, 1, 1);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json()).containsEntry("code", "CONTRACTOR_NOT_ELIGIBLE");
        assertNothingSent();
    }

    @Test
    void anIssueNoLongerDispatchableIsRefusedWithItsState() {
        givenADraftFor(PLUMBING_CO);
        events.issueStateChanged(issueId, BUILDING_A, "cancelled", 4, false, "plumbing");

        var response = approve(ADMIN_A, interventionId, 1, 1);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json()).containsEntry("code", "INVALID_TRANSITION").containsEntry("currentState", "cancelled");
        assertNothingSent();
    }

    @Test
    void onlyAnAdministratorOfTheBuildingApprovesAndBadRequestsChangeNothing() {
        givenADraftFor(PLUMBING_CO);
        for (String sub : List.of(RESIDENT_A1, CONTRACTOR, ADMIN_B)) {
            assertThat(approve(sub, interventionId, 1, 1).status()).as(sub).isEqualTo(404);
        }
        assertThat(approve(ADMIN_A, UUID.randomUUID().toString(), 1, 1).status()).isEqualTo(404);
        assertThat(approve(ADMIN_A, interventionId, 1, null).json().get("errors"))
                .isEqualTo(List.of(Map.of("field", "proposalVersion", "reason", "required")));
        assertThat(approve(ADMIN_A, interventionId, 1, 2).json().get("errors"))
                .isEqualTo(List.of(Map.of("field", "proposalVersion", "reason", "not_current")));
        var stale = approve(ADMIN_A, interventionId, 5, 1);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.json()).containsEntry("code", "VERSION_CONFLICT").containsEntry("currentVersion", 1);
        assertNothingSent();
        assertThat(count("idempotency_keys")).as("refused requests leave no key").isEqualTo(1);
    }
}
