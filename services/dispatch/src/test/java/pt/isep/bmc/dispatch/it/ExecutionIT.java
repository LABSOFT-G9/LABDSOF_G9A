package pt.isep.bmc.dispatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.support.Fixtures.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pt.isep.bmc.dispatch.adapters.in.messaging.IssueEvents;
import pt.isep.bmc.dispatch.application.port.in.ApplyIssueClosureUseCase;
import pt.isep.bmc.dispatch.support.Api;
import pt.isep.bmc.dispatch.support.IntegrationTestSupport;
import tools.jackson.databind.json.JsonMapper;

/**
 * US-09 over HTTP and MySQL: start, completion and rework, and Issue's
 * closure events (synthetic until EN-02-02 wires the consumer).
 */
class ExecutionIT extends IntegrationTestSupport {

    private static final String SUMMARY = "Replaced the cracked pipe joint; light fitting dried and tested.";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    ApplyIssueClosureUseCase closures;

    private String bookedIntervention() {
        String interventionId = agreedIntervention();
        runBookingWorker();
        return interventionId;
    }

    private Api.Response post(String sub, String interventionId, String action, long version, Map<String, Object> body) {
        return command(sub, "/interventions/" + interventionId + "/" + action, version, UUID.randomUUID().toString(),
                body);
    }

    private static Map<String, Object> completion(String reason) {
        Map<String, Object> body = new HashMap<>(Map.of("summary", SUMMARY, "performedAt", "2026-11-26T10:30:00Z"));
        if (reason != null) {
            body.put("reason", reason);
        }
        return body;
    }

    private void issueEvent(String type, String issueId) {
        closures.apply(IssueEvents.closure(type, JSON.readTree("{\"issueId\":\"" + issueId + "\"}"), "c-issue"));
    }

    private Map<String, Object> view(String sub, String interventionId) {
        return api.get("/interventions/" + interventionId, tokens.token(sub)).json();
    }

    @Test
    void startCompletionReworkAndASecondCompletionKeepEveryReport() {
        String id = bookedIntervention();

        assertThat(post(CONTRACTOR, id, "start", 4, Map.of()).json())
                .isEqualTo(Map.of("interventionId", id, "state", "in_progress", "version", 5));
        var reported = post(CONTRACTOR, id, "completion", 5, completion(null));
        assertThat(reported.header("ETag")).isEqualTo("\"6\"");
        assertThat(reported.json()).containsEntry("state", "completion_reported");
        Map<String, Object> event = event("CompletionReported");
        assertThat(Api.map(event.get("data"))).containsEntry("interventionState", "completion_reported")
                .doesNotContainKey("summary");

        assertThat(post(ADMIN_A, id, "rework", 6, Map.of("reason", "The light still flickers.")).json())
                .containsEntry("state", "in_progress");
        assertThat(event("ReworkRequested")).containsEntry("aggregateversion", 7);
        post(CONTRACTOR, id, "completion", 7, completion(null));

        List<Object> reports = Api.list(view(ADMIN_A, id).get("completions"));
        assertThat(reports).hasSize(2);
        assertThat(Api.map(reports.getFirst())).containsEntry("number", 1).containsEntry("summary", SUMMARY)
                .containsEntry("reporterRole", "contractor").containsEntry("reworkReason", "The light still flickers.");
        assertThat(Api.map(reports.get(1))).containsEntry("reworkReason", null);
        assertThat(Api.list(view(CONTRACTOR, id).get("completions"))).hasSize(2);
        assertThat(outboxEvents().toString()).doesNotContain(SUMMARY, "flickers");
    }

    @Test
    void anAdministratorReportsForTheContractorWithAReasonTheContractorDoesNotSee() {
        String id = bookedIntervention();

        assertThat(post(ADMIN_A, id, "completion", 4, completion(null)).json().get("errors"))
                .isEqualTo(List.of(Map.of("field", "reason", "reason", "required")));
        assertThat(post(ADMIN_A, id, "completion", 4, completion("Confirmed by phone.")).status()).isEqualTo(200);

        assertThat(Api.map(Api.list(view(ADMIN_A, id).get("completions")).getFirst()))
                .containsEntry("reporterRole", "administrator").containsEntry("reason", "Confirmed by phone.");
        assertThat(Api.map(Api.list(view(CONTRACTOR, id).get("completions")).getFirst())).containsEntry("reason", null);
    }

    @Test
    void onlyTheRightPeopleActAndOnlyInTheRightState() {
        String id = bookedIntervention();
        for (String sub : List.of(RESIDENT_A1, ADMIN_B, UUID.randomUUID().toString())) {
            assertThat(post(sub, id, "completion", 4, completion(null)).status()).as(sub).isEqualTo(404);
        }
        assertThat(post(ADMIN_A, id, "start", 4, Map.of()).status()).as("the contractor starts").isEqualTo(404);
        assertThat(post(CONTRACTOR, id, "rework", 4, Map.of("reason", "x")).status()).isEqualTo(404);
        assertThat(post(ADMIN_A, id, "rework", 4, Map.of("reason", "Too early.")).json())
                .containsEntry("code", "INVALID_TRANSITION").containsEntry("currentState", "booked");
        assertThat(post(CONTRACTOR, id, "completion", 3, completion(null)).json())
                .containsEntry("code", "VERSION_CONFLICT");
        Map<String, Object> future = new HashMap<>(Map.of("summary", "short", "performedAt", "2099-01-01T00:00:00Z"));
        assertThat(post(CONTRACTOR, id, "completion", 4, future).json().get("errors")).asList()
                .extracting(e -> Api.map(e).get("reason")).containsExactlyInAnyOrder("too_short", "in_future");
        String draftOnly = sentIntervention();
        assertThat(post(CONTRACTOR, draftOnly, "completion", 2, completion(null)).json())
                .containsEntry("code", "INVALID_TRANSITION").containsEntry("currentState", "sent");
    }

    @Test
    void issueResolvedClosesAReportedCompletionAndARepeatChangesNothing() {
        String id = bookedIntervention();
        post(CONTRACTOR, id, "completion", 4, completion(null));
        String issueId = (String) view(ADMIN_A, id).get("issueId");
        long events = count("outbox");

        issueEvent(IssueEvents.ISSUE_RESOLVED, issueId);
        issueEvent(IssueEvents.ISSUE_RESOLVED, issueId);

        assertThat(view(ADMIN_A, id)).containsEntry("state", "closed").containsEntry("version", 6);
        assertThat(count("outbox")).as("no event for a closure").isEqualTo(events);
    }

    @Test
    void aManualClosureWhileBookedCancelsTheBookingAtTheCalendar() {
        String id = bookedIntervention();
        String issueId = (String) view(ADMIN_A, id).get("issueId");

        issueEvent(IssueEvents.ISSUE_RESOLVED, issueId);

        Map<String, Object> pending = view(ADMIN_A, id);
        assertThat(pending).containsEntry("state", "cancellation_pending");
        assertThat(Api.map(pending.get("cancellation"))).containsEntry("reasonCode", "issue_resolved")
                .containsEntry("note", null);
        assertThat(Api.map(event("InterventionCancellationPending").get("data")))
                .containsEntry("reasonCode", "issue_resolved");
        runBookingWorker();

        assertThat(view(ADMIN_A, id)).containsEntry("state", "cancelled");
        assertThat(calendar.requests()).contains("POST /bookings/" + bookingOf(id).get("externalRef") + "/cancellation");
        assertThat(post(CONTRACTOR, id, "start", 6, Map.of()).json()).containsEntry("code", "INVALID_TRANSITION");
    }

    @Test
    void issueCancelledCancelsAnUnsentDraftAtOnce() {
        String issueId = triagedPlumbingIssue();
        String id = (String) draft(ADMIN_A, issueId, PLUMBING_CO).json().get("interventionId");

        issueEvent(IssueEvents.ISSUE_CANCELLED, issueId);

        assertThat(view(ADMIN_A, id)).containsEntry("state", "cancelled");
        assertThat(Api.map(event("InterventionCancelled").get("data"))).containsEntry("reasonCode", "issue_cancelled")
                .containsEntry("interventionState", "cancelled");
    }
}
