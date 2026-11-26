package pt.isep.bmc.dispatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.support.Fixtures.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.support.Api;
import pt.isep.bmc.dispatch.support.IntegrationTestSupport;

/**
 * US-07: the assigned contractor sees a request once it is sent and answers
 * it with {@code POST /interventions/{id}/response}; changed terms withdraw
 * an approval.
 */
class ContractorResponseIT extends IntegrationTestSupport {

    private static final String NOTE = "Away until March; try Aqua Plumbers.";

    private Map<String, Object> view(String sub, String interventionId) {
        return api.get("/interventions/" + interventionId, tokens.token(sub)).json();
    }

    private static Map<String, Object> counterproposal() {
        Map<String, Object> body = new HashMap<>();
        body.put("proposalVersion", 1);
        body.put("decision", "counterpropose");
        body.put("window", Map.of("start", "2099-11-06T09:00:00Z", "end", "2099-11-06T11:00:00Z"));
        body.put("terms", "Saturday morning; parts extra.");
        body.put("proposedCost", Map.of("amount", "120.00", "currency", "EUR"));
        return body;
    }

    @Test
    void theContractorSeesTheRequestOnlyOnceItIsSentAndOnlyWhatTheWorkNeeds() {
        String issueId = triagedPlumbingIssue();
        String interventionId = (String) draft(ADMIN_A, issueId, PLUMBING_CO).json().get("interventionId");
        assertThat(api.get("/interventions/" + interventionId, tokens.token(CONTRACTOR)).status()).isEqualTo(404);
        assertThat(Api.list(api.get("/interventions?assigned=me", tokens.token(CONTRACTOR)).json().get("items")))
                .isEmpty();

        approve(ADMIN_A, interventionId, 1, 1);

        var response = api.get("/interventions/" + interventionId, tokens.token(CONTRACTOR));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("ETag")).isEqualTo("\"2\"");
        Map<String, Object> assignment = response.json();
        assertThat(assignment).containsOnlyKeys("interventionId", "state", "version", "contractor", "work",
                "awaitingResponseTo", "agreedProposalVersion", "proposals", "updatedAt");
        assertThat(assignment).containsEntry("state", "sent").containsEntry("awaitingResponseTo", 1);
        assertThat(Api.map(assignment.get("work"))).containsEntry("category", "plumbing")
                .containsEntry("areaLabel", "Garage level -1");
        assertThat(Api.list(assignment.get("proposals"))).singleElement()
                .satisfies(p -> assertThat(Api.map(p)).containsEntry("scopeExtract", SCOPE)
                        .containsEntry("terms", TERMS));
        assertThat(response.body()).doesNotContain(issueId, ADMIN_A);

        var list = api.get("/interventions?assigned=me", tokens.token(CONTRACTOR));
        assertThat(Api.list(list.json().get("items"))).singleElement()
                .satisfies(item -> assertThat(Api.map(item)).containsEntry("interventionId", interventionId));
        assertThat(Api.list(api.get("/interventions?assigned=me", tokens.token(ADMIN_A)).json().get("items")))
                .as("nothing was sent to the administrator").isEmpty();
        assertThat(api.get("/interventions?assigned=you", tokens.token(CONTRACTOR)).status()).isEqualTo(400);
    }

    @Test
    void acceptanceAgreesTheVersionAndWritesContractorResponded() {
        String interventionId = sentIntervention();

        var response = respond(CONTRACTOR, interventionId, 2, accept(1));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("ETag")).isEqualTo("\"3\"");
        assertThat(response.json()).isEqualTo(Map.of("interventionId", interventionId, "state", "scheduling_pending",
                "version", 3, "proposalVersion", 1));
        Map<String, Object> event = outboxEvents().getLast();
        assertThat(event).containsEntry("type", "ContractorResponded").containsEntry("aggregateversion", 3);
        assertThat(Api.map(event.get("data"))).containsEntry("decision", "accept").containsEntry("proposalVersion", 1)
                .containsEntry("interventionState", "scheduling_pending");

        Map<String, Object> admin = view(ADMIN_A, interventionId);
        assertThat(admin).containsEntry("agreedProposalVersion", 1);
        assertThat(Api.map(Api.map(Api.list(admin.get("proposals")).getFirst()).get("response")))
                .containsEntry("decision", "accept").containsEntry("reasonCode", null);
    }

    @Test
    void aDeclineIsTerminalTheNoteStaysInDispatchAndTheIssueCanBeDispatchedAgain() {
        String interventionId = sentIntervention();
        String issueId = (String) view(ADMIN_A, interventionId).get("issueId");
        Map<String, Object> body = new HashMap<>(Map.of("proposalVersion", 1, "decision", "decline",
                "reasonCode", "unavailable", "note", NOTE));

        var response = respond(CONTRACTOR, interventionId, 2, body);

        assertThat(response.json()).containsEntry("state", "declined").containsEntry("version", 3);
        Map<String, Object> event = outboxEvents().getLast();
        assertThat(event).containsEntry("type", "InterventionDeclined");
        assertThat(event.get("data")).isEqualTo(Map.of("issueId", issueId, "reasonCode", "unavailable",
                "interventionState", "declined"));
        assertThat(outboxEvents().toString()).doesNotContain("Away until March");
        assertThat(Api.map(Api.map(Api.list(view(ADMIN_A, interventionId).get("proposals")).getFirst())
                .get("response"))).containsEntry("reasonCode", "unavailable").containsEntry("note", NOTE);
        assertThat(draft(ADMIN_A, issueId, AQUA_PLUMBERS).status()).as("another contractor, a new intervention")
                .isEqualTo(201);
    }

    @Test
    void aCounterproposalNeedsApprovalAndApprovingItAgreesTheTerms() {
        String interventionId = sentIntervention();

        var response = respond(CONTRACTOR, interventionId, 2, counterproposal());

        assertThat(response.json()).isEqualTo(Map.of("interventionId", interventionId, "state", "awaiting_approval",
                "version", 3, "proposalVersion", 2));
        Map<String, Object> mine = view(CONTRACTOR, interventionId);
        assertThat(mine).containsEntry("awaitingResponseTo", null);
        List<Object> proposals = Api.list(mine.get("proposals"));
        assertThat(proposals).hasSize(2);
        assertThat(Api.map(proposals.get(1))).containsEntry("authorRole", "contractor")
                .containsEntry("scopeExtract", SCOPE).containsEntry("terms", "Saturday morning; parts extra.")
                .containsEntry("proposedCost", Map.of("amount", "120.00", "currency", "EUR"))
                .containsEntry("approvedAt", null);
        assertThat(replace(ADMIN_A, interventionId, 3, AQUA_PLUMBERS).json())
                .as("the contractor already has the request").containsEntry("code", "CONTRACTOR_ALREADY_CONTACTED");

        var approved = approve(ADMIN_A, interventionId, 3, 2);

        assertThat(approved.json()).containsEntry("state", "scheduling_pending").containsEntry("version", 4);
        assertThat(view(ADMIN_A, interventionId)).containsEntry("agreedProposalVersion", 2);
        assertThat(Api.map(outboxEvents().getLast().get("data"))).containsEntry("approvedProposalVersion", 2)
                .containsEntry("interventionState", "scheduling_pending");
    }

    @Test
    void changedTermsWithdrawTheApprovalAndStayHiddenFromTheContractorUntilApproved() {
        String interventionId = sentIntervention();

        var changed = changeTerms(ADMIN_A, interventionId, 2);

        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.json()).isEqualTo(Map.of("interventionId", interventionId, "state", "awaiting_approval",
                "version", 3, "proposalVersion", 2));
        assertThat(respond(CONTRACTOR, interventionId, 3, accept(1)).json())
                .containsEntry("code", "INVALID_TRANSITION").containsEntry("currentState", "awaiting_approval");
        Map<String, Object> mine = view(CONTRACTOR, interventionId);
        assertThat(Api.list(mine.get("proposals"))).as("only the version once sent").hasSize(1);
        assertThat(mine.toString()).doesNotContain("report the cost before any extra work");

        assertThat(approve(ADMIN_A, interventionId, 3, 2).json()).containsEntry("state", "sent");
        assertThat(Api.list(view(CONTRACTOR, interventionId).get("proposals"))).hasSize(2);
        assertThat(respond(CONTRACTOR, interventionId, 4, accept(2)).json())
                .containsEntry("state", "scheduling_pending");
    }

    @Test
    void acceptanceRacingChangedTermsLeavesOneWinner() throws Exception {
        String interventionId = sentIntervention();
        String issueId = (String) view(ADMIN_A, interventionId).get("issueId");
        CompletableFuture<Api.Response> acceptance;
        CompletableFuture<Api.Response> terms;
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement(
                    "SELECT issue_id FROM issue_projection WHERE issue_id = ? FOR UPDATE")) {
                lock.setString(1, issueId);
                lock.executeQuery();
            }
            acceptance = CompletableFuture.supplyAsync(() -> respond(CONTRACTOR, interventionId, 2, accept(1)));
            terms = CompletableFuture.supplyAsync(() -> changeTerms(ADMIN_A, interventionId, 2));
            Thread.sleep(2_000);
            blocker.commit();
        }

        List<Integer> statuses = List.of(acceptance.get().status(), terms.get().status());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        String state = jdbc.sql("SELECT state FROM interventions WHERE id = ?").param(interventionId)
                .query(String.class).single();
        if (acceptance.get().status() == 200) {
            assertThat(state).isEqualTo("scheduling_pending");
            assertThat(count("proposal_versions")).isEqualTo(1);
        } else {
            assertThat(state).isEqualTo("awaiting_approval");
            assertThat(count("contractor_responses")).isZero();
        }
    }

    @Test
    void onlyTheAssignedContractorAnswersAndBadAnswersChangeNothing() {
        String interventionId = sentIntervention();
        assertThat(respond(ADMIN_A, interventionId, 2, accept(1)).status()).as("the administrator").isEqualTo(404);
        for (String sub : List.of(RESIDENT_A1, ADMIN_B, UUID.randomUUID().toString())) {
            assertThat(respond(sub, interventionId, 2, accept(1)).status()).as(sub).isEqualTo(404);
            assertThat(api.get("/interventions/" + interventionId, tokens.token(sub)).status()).as(sub).isEqualTo(404);
        }
        assertThat(respond(CONTRACTOR, interventionId, 2, new HashMap<>(Map.of("proposalVersion", 1,
                "decision", "decline"))).json().get("errors"))
                .isEqualTo(List.of(Map.of("field", "reasonCode", "reason", "required")));
        Map<String, Object> numberAmount = counterproposal();
        numberAmount.put("proposedCost", Map.of("amount", 120, "currency", "EUR"));
        assertThat(respond(CONTRACTOR, interventionId, 2, numberAmount).status())
                .as("an amount is a string, so nothing is rounded").isEqualTo(400);
        assertThat(respond(CONTRACTOR, interventionId, 2, accept(7)).json().get("errors"))
                .isEqualTo(List.of(Map.of("field", "proposalVersion", "reason", "not_current")));
        assertThat(respond(CONTRACTOR, interventionId, 1, accept(1)).json())
                .containsEntry("code", "VERSION_CONFLICT").containsEntry("currentVersion", 2);
        assertThat(count("contractor_responses")).isZero();
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void repeatingAnAnswerReplaysItWithoutASecondEvent() {
        String interventionId = sentIntervention();
        String key = UUID.randomUUID().toString();

        var first = command(CONTRACTOR, "/interventions/" + interventionId + "/response", 2, key, counterproposal());
        var again = command(CONTRACTOR, "/interventions/" + interventionId + "/response", 2, key, counterproposal());

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json()).isEqualTo(first.json());
        assertThat(count("outbox")).isEqualTo(2);
        assertThat(count("proposal_versions")).isEqualTo(2);
        assertThat(command(CONTRACTOR, "/interventions/" + interventionId + "/response", 2, key, accept(1)).status())
                .isEqualTo(422);
    }
}
