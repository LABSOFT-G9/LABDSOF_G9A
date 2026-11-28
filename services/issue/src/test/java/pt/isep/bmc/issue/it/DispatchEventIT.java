package pt.isep.bmc.issue.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.issue.support.Fixtures.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pt.isep.bmc.issue.adapters.in.messaging.DispatchEvents;
import pt.isep.bmc.issue.application.command.DispatchEventResult;
import pt.isep.bmc.issue.application.port.in.ApplyDispatchEventUseCase;
import pt.isep.bmc.issue.support.Api.Response;
import pt.isep.bmc.issue.support.IntegrationTestSupport;
import tools.jackson.databind.json.JsonMapper;

/**
 * US-07-03 on real MySQL: Dispatch's events, read as Dispatch's outbox writes
 * them, move the issue in order by version; repeated, concurrent, late and
 * misplaced events have no duplicate or backward effect; and the projected
 * states never become a field anyone can edit (US-02).
 */
class DispatchEventIT extends IntegrationTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String WINDOW = "{\"start\": \"2026-12-02T09:00:00Z\", \"end\": \"2026-12-02T12:00:00Z\"}";

    @Autowired
    ApplyDispatchEventUseCase dispatch;

    String triagedIssue() {
        Response report = api.post("/reports", tokens.token(RESIDENT_A1), UUID.randomUUID().toString(),
                report(BUILDING_A, AREA_A_GARAGE, "The garage door on level -1 does not close."));
        assertThat(report.status()).isEqualTo(201);
        String issueId = (String) report.json().get("issueId");
        assertThat(triage(ADMIN_A, issueId, 1, "plumbing", "high", "Checked the photos.").status()).isEqualTo(200);
        return issueId;
    }

    DispatchEventResult send(String eventId, String type, String issueId, String intervention, long version,
                             String state, String extra) {
        String message = """
                {"specversion": "1.0", "id": "%s", "type": "%s", "source": "/dispatch", "subject": "%s",
                 "time": "2026-11-28T10:00:00Z", "datacontenttype": "application/json", "schemaversion": 1,
                 "aggregateversion": %d, "buildingid": "%s", "correlationid": "corr-dispatch-it",
                 "data": {"issueId": "%s", "interventionState": "%s"%s}}
                """.formatted(eventId, type, intervention, version, BUILDING_A, issueId, state, extra);
        return dispatch.apply(DispatchEvents.parse(JSON.readTree(message)));
    }

    DispatchEventResult send(String type, String issueId, String intervention, long version, String state,
                             String extra) {
        return send(UUID.randomUUID().toString(), type, issueId, intervention, version, state, extra);
    }

    Map<String, Object> issue(String issueId) {
        return api.get("/issues/" + issueId, tokens.token(ADMIN_A)).json();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> history(String issueId, String sub) {
        return (List<Map<String, Object>>) api.get("/issues/" + issueId + "/history", tokens.token(sub)).json()
                .get("items");
    }

    long stateChanges(String issueId) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox WHERE event_type = 'IssueStateChanged' AND aggregate_id = :id")
                .param("id", issueId).query(Long.class).single();
    }

    @Test
    void theInterventionMovesTheIssueInOrderAndCompletionAwaitsValidation() {
        String issueId = triagedIssue();
        String intervention = UUID.randomUUID().toString();
        long changesBefore = stateChanges(issueId);

        assertThat(send("InterventionApproved", issueId, intervention, 2, "sent",
                ", \"contractorUserId\": \"" + CONTRACTOR + "\", \"contractorDisplayName\": \"Synthetic Plumbing Co.\","
                        + " \"approvedProposalVersion\": 1")).isEqualTo(DispatchEventResult.APPLIED);
        assertThat(issue(issueId)).containsEntry("state", "in_coordination").containsEntry("version", 3);

        // US-02: a projected state is not a field: triage is refused once an intervention is under way.
        Response retriage = triage(ADMIN_A, issueId, 3, "electrical", "low", "Trying to move it back.");
        assertThat(retriage.status()).isEqualTo(409);
        assertThat(retriage.json()).containsEntry("code", "INVALID_TRANSITION");

        send("ContractorResponded", issueId, intervention, 3, "scheduling_pending",
                ", \"decision\": \"accept\", \"proposalVersion\": 1");
        assertThat(issue(issueId)).containsEntry("state", "in_coordination").containsEntry("version", 3);
        send("BookingConfirmed", issueId, intervention, 4, "booked",
                ", \"window\": " + WINDOW + ", \"source\": \"manual\"");
        send("InterventionStarted", issueId, intervention, 5, "in_progress", "");
        send("CompletionReported", issueId, intervention, 6, "completion_reported", "");

        Map<String, Object> done = issue(issueId);
        assertThat(done).containsEntry("state", "awaiting_validation").containsEntry("version", 6);
        // Dispatch's copy of the issue hears of every state change, and only of those.
        assertThat(stateChanges(issueId) - changesBefore).isEqualTo(4);
        assertThat(jdbc.sql("SELECT contractor_user_id FROM issue_interventions WHERE intervention_id = :id")
                .param("id", intervention).query(String.class).single()).isEqualTo(CONTRACTOR);

        List<Map<String, Object>> internal = history(issueId, ADMIN_A);
        assertThat(internal).extracting(entry -> entry.get("type")).containsExactly("report_received",
                "triage_decided", "intervention_approved", "contractor_accepted", "booking_confirmed_manually",
                "intervention_started", "completion_reported");
        assertThat(internal.get(3)).containsEntry("actorRole", "contractor").containsEntry("code", "accept")
                .containsEntry("applied", true);
        assertThat(internal.get(4)).containsEntry("actorRole", "administrator")
                .containsEntry("window", Map.of("start", "2026-12-02T09:00:00Z", "end", "2026-12-02T12:00:00Z"));
        // Residents of the building see how the work goes, never a code.
        assertThat(history(issueId, RESIDENT_A1)).extracting(entry -> entry.get("type")).containsExactly(
                "report_received", "triage_decided", "intervention_approved", "contractor_accepted",
                "booking_confirmed_manually", "intervention_started", "completion_reported");
        assertThat(history(issueId, RESIDENT_A1)).allSatisfy(entry -> assertThat(entry.get("code")).isNull());
    }

    @Test
    void repeatedLateAndOutOfOrderEventsHaveNoDuplicateOrBackwardEffect() {
        String issueId = triagedIssue();
        String intervention = UUID.randomUUID().toString();

        // The booking (version 4) arrives before the approval (version 2).
        String booking = UUID.randomUUID().toString();
        assertThat(send(booking, "BookingConfirmed", issueId, intervention, 4, "booked",
                ", \"window\": " + WINDOW + ", \"source\": \"calendar\"")).isEqualTo(DispatchEventResult.APPLIED);
        assertThat(send(booking, "BookingConfirmed", issueId, intervention, 4, "booked",
                ", \"window\": " + WINDOW + ", \"source\": \"calendar\"")).isEqualTo(DispatchEventResult.DUPLICATE);
        assertThat(send("InterventionApproved", issueId, intervention, 2, "sent",
                ", \"contractorUserId\": \"" + CONTRACTOR + "\"")).isEqualTo(DispatchEventResult.HISTORY_ONLY);
        // A booking-state event carries the intervention's current version: history only.
        assertThat(send("BookingStateChanged", issueId, intervention, 4, "booked", ", \"bookingState\": \"uncertain\""))
                .isEqualTo(DispatchEventResult.HISTORY_ONLY);

        assertThat(issue(issueId)).containsEntry("state", "scheduled").containsEntry("version", 3);
        List<Map<String, Object>> internal = history(issueId, ADMIN_A);
        assertThat(internal).extracting(entry -> entry.get("type")).containsExactly("report_received",
                "triage_decided", "booking_confirmed", "intervention_approved", "booking_state_changed");
        assertThat(internal).extracting(entry -> entry.get("applied")).containsExactly(null, null, true, false,
                false);
        assertThat(history(issueId, RESIDENT_A1)).extracting(entry -> entry.get("type"))
                .doesNotContain("booking_state_changed");
    }

    @Test
    void concurrentDeliveriesOfOneEventApplyItOnce() throws Exception {
        String issueId = triagedIssue();
        String intervention = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<DispatchEventResult>> deliveries = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                deliveries.add(() -> send(eventId, "InterventionApproved", issueId, intervention, 2, "sent",
                        ", \"contractorUserId\": \"" + CONTRACTOR + "\""));
            }
            List<DispatchEventResult> results = new ArrayList<>();
            for (Future<DispatchEventResult> result : pool.invokeAll(deliveries)) {
                results.add(result.get());
            }
            assertThat(results).containsOnlyOnce(DispatchEventResult.APPLIED)
                    .filteredOn(r -> r != DispatchEventResult.APPLIED).containsOnly(DispatchEventResult.DUPLICATE);
        } finally {
            pool.shutdownNow();
        }
        assertThat(issue(issueId)).containsEntry("state", "in_coordination").containsEntry("version", 3);
        assertThat(history(issueId, ADMIN_A)).filteredOn(entry -> "intervention_approved".equals(entry.get("type")))
                .hasSize(1);
    }

    @Test
    void aDeclineReturnsTheIssueToTriageAndTheReasonStaysWithTheAdministrators() {
        String issueId = triagedIssue();
        String first = UUID.randomUUID().toString();
        send("InterventionApproved", issueId, first, 2, "sent", ", \"contractorUserId\": \"" + CONTRACTOR + "\"");

        assertThat(send("InterventionDeclined", issueId, first, 3, "declined", ", \"reasonCode\": \"unavailable\""))
                .isEqualTo(DispatchEventResult.APPLIED);
        assertThat(issue(issueId)).containsEntry("state", "triaged").containsEntry("version", 4);
        // Manual triage is possible again, and so is the next intervention.
        assertThat(triage(ADMIN_A, issueId, 4, "plumbing", "critical", "No contractor yet; it got worse.").status())
                .isEqualTo(200);
        String second = UUID.randomUUID().toString();
        assertThat(send("InterventionApproved", issueId, second, 2, "sent", ", \"contractorUserId\": \"c-2\""))
                .isEqualTo(DispatchEventResult.APPLIED);
        // A late event of the declined intervention changes nothing.
        assertThat(send("InterventionStarted", issueId, first, 4, "in_progress", ""))
                .isEqualTo(DispatchEventResult.HISTORY_ONLY);
        assertThat(issue(issueId)).containsEntry("state", "in_coordination").containsEntry("version", 6);

        assertThat(history(issueId, ADMIN_A)).filteredOn(e -> "contractor_declined".equals(e.get("type")))
                .singleElement().satisfies(e -> assertThat(e).containsEntry("code", "unavailable"));
        assertThat(history(issueId, RESIDENT_A1)).filteredOn(e -> "contractor_declined".equals(e.get("type")))
                .singleElement().satisfies(e -> assertThat(e.get("code")).isNull());
    }

    @Test
    void eventsThatFitNoRuleAreKeptForInspectionWithNoEffect() {
        String received = (String) api.post("/reports", tokens.token(RESIDENT_A1), UUID.randomUUID().toString(),
                report(BUILDING_A, AREA_A_GARAGE, "The bin room door is stuck open.")).json().get("issueId");
        String unknown = UUID.randomUUID().toString();

        assertThat(send("InterventionApproved", received, UUID.randomUUID().toString(), 2, "sent",
                ", \"contractorUserId\": \"" + CONTRACTOR + "\"")).isEqualTo(DispatchEventResult.FAILED);
        assertThat(send("InterventionApproved", unknown, UUID.randomUUID().toString(), 2, "sent",
                ", \"contractorUserId\": \"" + CONTRACTOR + "\"")).isEqualTo(DispatchEventResult.FAILED);

        assertThat(issue(received)).containsEntry("state", "received").containsEntry("version", 1);
        assertThat(history(received, ADMIN_A)).extracting(e -> e.get("type")).containsExactly("report_received");
        assertThat(jdbc.sql("SELECT reason FROM dispatch_events WHERE outcome = 'failed' ORDER BY reason")
                .query(String.class).list()).containsExactly("not_dispatchable", "unknown_issue");
        assertThat(count("issue_interventions")).isZero();
    }
}
