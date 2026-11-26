package pt.isep.bmc.dispatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.support.Fixtures.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.support.Api;
import pt.isep.bmc.dispatch.support.IntegrationTestSupport;

/**
 * US-08 over HTTP and MySQL, with a calendar that fails on demand: the
 * agreed window is booked once, uncertain outcomes are reconciled, and the
 * administrator confirms by hand, asks again, reconciles or cancels.
 */
class BookingIT extends IntegrationTestSupport {

    private static final String REASON = "Agreed by phone with the contractor.";

    private Api.Response manual(String sub, String interventionId, long version) {
        Map<String, Object> body = new HashMap<>();
        body.put("window", Map.of("start", "2099-11-06T14:00:00Z", "end", "2099-11-06T16:00:00Z"));
        body.put("source", "phone");
        body.put("reason", REASON);
        return command(sub, "/interventions/" + interventionId + "/manual-confirmation", version,
                UUID.randomUUID().toString(), body);
    }

    private Api.Response cancel(String interventionId, long version, String reasonCode) {
        Map<String, Object> body = new HashMap<>(Map.of("reasonCode", reasonCode, "note", "Residents fixed it."));
        return command(ADMIN_A, "/interventions/" + interventionId + "/cancellation", version,
                UUID.randomUUID().toString(), body);
    }

    private Map<String, Object> intervention(String interventionId) {
        return api.get("/interventions/" + interventionId, tokens.token(ADMIN_A)).json();
    }

    @Test
    void theAgreedWindowIsBookedOnceWithOnlyTheNecessaryContext() {
        String interventionId = agreedIntervention();
        assertThat(bookingOf(interventionId)).containsEntry("state", "requested").containsEntry("source", null);
        assertThat(calendar.requests()).as("saved before any call").isEmpty();

        assertThat(runBookingWorker()).isEqualTo(1);

        Map<String, Object> request = calendar.received().getFirst();
        String bookingId = (String) bookingOf(interventionId).get("bookingId");
        assertThat(request).containsEntry("key", bookingId).containsEntry("requestId", bookingId)
                .containsEntry("contractorRef", "SIM-CTR-001").containsEntry("buildingCode", BUILDING_A)
                .containsEntry("summary", "Plumbing intervention")
                .containsEntry("window", Map.of("start", "2099-11-05T09:00:00Z", "end", "2099-11-05T12:00:00Z"));
        assertThat(request.toString()).doesNotContain(SCOPE, TERMS);

        Map<String, Object> booked = intervention(interventionId);
        assertThat(booked).containsEntry("state", "booked").containsEntry("version", 4);
        assertThat(Api.map(booked.get("booking"))).containsEntry("state", "confirmed")
                .containsEntry("source", "calendar").containsEntry("pendingAction", "verify");
        assertThat((String) Api.map(booked.get("booking")).get("externalRef")).startsWith("SIM-BK-");
        Map<String, Object> event = outboxEvents().getLast();
        assertThat(event).containsEntry("type", "BookingConfirmed").containsEntry("aggregateversion", 4);
        assertThat(Api.map(event.get("data"))).containsEntry("source", "calendar")
                .containsEntry("interventionState", "booked")
                .containsEntry("window", Map.of("start", "2099-11-05T09:00:00Z", "end", "2099-11-05T12:00:00Z"));
        assertThat(Api.map(api.get("/interventions/" + interventionId, tokens.token(CONTRACTOR)).json().get("booking")))
                .containsEntry("state", "confirmed").containsEntry("source", "calendar");
    }

    @Test
    void aTimeoutAfterCreationIsReconciledAndLeavesOneBooking() {
        String interventionId = agreedIntervention();
        calendar.script("create", "timeout_after_create");

        runBookingWorker();
        assertThat(bookingOf(interventionId)).containsEntry("state", "uncertain")
                .containsEntry("pendingAction", "reconcile");
        assertThat(intervention(interventionId)).containsEntry("state", "scheduling_pending");
        runBookingWorker();

        assertThat(bookingOf(interventionId)).containsEntry("state", "confirmed");
        assertThat(calendar.bookingCount()).isEqualTo(1);
        assertThat(calendar.requests()).containsExactly("POST /bookings", "GET /bookings");
        assertThat(Api.list(bookingOf(interventionId).get("calls"))).extracting(c -> Api.map(c).get("outcome"))
                .containsExactly("unknown", "booked");
    }

    @Test
    void aConflictIsVisibleAndTheAdministratorConfirmsByHand() {
        String interventionId = agreedIntervention();
        calendar.script("create", "conflict");
        runBookingWorker();
        assertThat(bookingOf(interventionId)).containsEntry("state", "rejected")
                .containsEntry("reasonCode", "slot_conflict");
        assertThat(Api.map(outboxEvents().getLast().get("data"))).containsEntry("bookingState", "rejected");

        var response = manual(ADMIN_A, interventionId, 3);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json()).isEqualTo(Map.of("interventionId", interventionId, "state", "booked",
                "version", 4, "bookingState", "confirmed"));
        Map<String, Object> booking = bookingOf(interventionId);
        assertThat(booking).containsEntry("source", "manual").containsEntry("pendingAction", null)
                .containsEntry("bookedWindow", Map.of("start", "2099-11-06T14:00:00Z", "end", "2099-11-06T16:00:00Z"));
        assertThat(Api.map(booking.get("manual"))).containsEntry("source", "phone").containsEntry("reason", REASON);
        assertThat(Api.map(outboxEvents().getLast().get("data"))).containsEntry("source", "manual");
        assertThat(outboxEvents().toString()).doesNotContain(REASON);
    }

    @Test
    void anotherRequestAfterAFailureIsANewBooking() {
        String interventionId = agreedIntervention();
        calendar.script("create", "unavailable", "unavailable", "unavailable", "unavailable", "unavailable");
        for (int i = 0; i < 5; i++) {
            runBookingWorker();
        }
        assertThat(bookingOf(interventionId)).containsEntry("state", "failed")
                .containsEntry("reasonCode", "retries_exhausted");

        var again = command(ADMIN_A, "/interventions/" + interventionId + "/booking", 3, UUID.randomUUID().toString(),
                Map.of());
        assertThat(again.json()).containsEntry("bookingState", "requested").containsEntry("version", 4);
        runBookingWorker();

        assertThat(intervention(interventionId)).containsEntry("state", "booked");
        assertThat(Api.list(intervention(interventionId).get("bookings"))).extracting(b -> Api.map(b).get("state"))
                .containsExactly("failed", "confirmed");
    }

    @Test
    void cancellingABookedInterventionWaitsForTheCalendar() {
        String interventionId = agreedIntervention();
        runBookingWorker();
        calendar.script("cancel", "unavailable");

        var pending = cancel(interventionId, 4, "rescheduling");

        assertThat(pending.json()).containsEntry("state", "cancellation_pending");
        assertThat(outboxEvents().getLast()).containsEntry("type", "InterventionCancellationPending");
        runBookingWorker();
        assertThat(intervention(interventionId)).containsEntry("state", "cancellation_pending");
        runBookingWorker();

        Map<String, Object> cancelled = intervention(interventionId);
        assertThat(cancelled).containsEntry("state", "cancelled")
                .containsEntry("cancellation", Map.of("reasonCode", "rescheduling", "note", "Residents fixed it."));
        assertThat(Api.map(cancelled.get("booking"))).containsEntry("state", "cancelled");
        Map<String, Object> event = outboxEvents().getLast();
        assertThat(event).containsEntry("type", "InterventionCancelled");
        assertThat(Api.map(event.get("data"))).containsEntry("reasonCode", "rescheduling");
        assertThat(outboxEvents().toString()).doesNotContain("Residents fixed it.");
        assertThat(draft(ADMIN_A, (String) cancelled.get("issueId"), AQUA_PLUMBERS).status())
                .as("the issue can get another intervention").isEqualTo(201);
    }

    @Test
    void aBookingLostByTheCalendarIsADiscrepancyFoundOnDemand() {
        String interventionId = agreedIntervention();
        runBookingWorker();
        calendar.loseAll();

        var asked = api.send("POST", "/interventions/" + interventionId + "/booking/reconciliation",
                tokens.token(ADMIN_A), Map.of(), null);
        assertThat(asked.status()).isEqualTo(202);
        runBookingWorker();

        assertThat(bookingOf(interventionId)).containsEntry("state", "discrepancy")
                .containsEntry("reasonCode", "missing_at_provider");
        assertThat(calendar.bookingCount()).as("never silently recreated").isZero();
        assertThat(Api.map(outboxEvents().getLast().get("data"))).containsEntry("bookingState", "discrepancy");
    }

    @Test
    void aContractorWithoutACalendarReferenceCannotBeBookedAutomatically() {
        jdbc.sql("UPDATE contractors SET calendar_ref = NULL WHERE id = ?").param(PLUMBING_CO).update();

        String interventionId = agreedIntervention();

        assertThat(bookingOf(interventionId)).containsEntry("state", "failed")
                .containsEntry("reasonCode", "no_calendar_reference");
        assertThat(runBookingWorker()).isZero();
        assertThat(manual(ADMIN_A, interventionId, 3).status()).isEqualTo(200);
    }

    @Test
    void onlyTheBuildingsAdministratorUsesTheBookingCommands() {
        String interventionId = agreedIntervention();
        for (String sub : List.of(CONTRACTOR, RESIDENT_A1, ADMIN_B)) {
            assertThat(manual(sub, interventionId, 3).status()).as(sub).isEqualTo(404);
        }
        assertThat(command(CONTRACTOR, "/interventions/" + interventionId + "/cancellation", 3,
                UUID.randomUUID().toString(), Map.of("reasonCode", "other")).status()).isEqualTo(404);
        Map<String, Object> bad = new HashMap<>(Map.of("source", "fax"));
        assertThat(command(ADMIN_A, "/interventions/" + interventionId + "/manual-confirmation", 3,
                UUID.randomUUID().toString(), bad).json().get("errors"))
                .asList().extracting(e -> Api.map(e).get("field"))
                .containsExactlyInAnyOrder("window.start", "window.end", "source", "reason");
        assertThat(manual(ADMIN_A, interventionId, 1).json()).containsEntry("code", "VERSION_CONFLICT");
    }
}
