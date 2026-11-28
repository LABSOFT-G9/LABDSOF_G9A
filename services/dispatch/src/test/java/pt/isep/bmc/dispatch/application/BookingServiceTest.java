package pt.isep.bmc.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.dispatch.application.ApplicationFixtures.*;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.application.command.BookingCommandResult;
import pt.isep.bmc.dispatch.application.command.BookingService;
import pt.isep.bmc.dispatch.application.command.BookingWorker;
import pt.isep.bmc.dispatch.application.command.CancelInterventionCommand;
import pt.isep.bmc.dispatch.application.command.ManualConfirmationCommand;
import pt.isep.bmc.dispatch.application.command.RequestBookingCommand;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.domain.booking.Booking;
import pt.isep.bmc.dispatch.domain.booking.BookingAction;
import pt.isep.bmc.dispatch.domain.booking.BookingSource;
import pt.isep.bmc.dispatch.domain.booking.BookingState;
import pt.isep.bmc.dispatch.domain.booking.CalendarReply;
import pt.isep.bmc.dispatch.domain.event.BookingConfirmed;
import pt.isep.bmc.dispatch.domain.event.InterventionCancellationPending;
import pt.isep.bmc.dispatch.domain.event.InterventionCancelled;
import pt.isep.bmc.dispatch.domain.intervention.CancelReason;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.FieldViolation;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.UserId;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;
import pt.isep.bmc.dispatch.domain.shared.VersionConflictException;

/** US-08: manual confirmation, another request, reconciliation on demand and cancellation. */
class BookingServiceTest {

    static final String MANUAL_START = "2026-11-29T14:00:00Z";
    static final String MANUAL_END = "2026-11-29T16:00:00Z";

    AgreementFixtures f = new AgreementFixtures();
    FakeCalendar calendar = new FakeCalendar();
    BookingService service;
    BookingWorker worker;

    @BeforeEach
    void setUp() {
        service = new BookingService(f.ports, f.ports, f.ports, f.ports, f.ports, f.ports, f.ports, f.ports, f.ports,
                f.clock);
        worker = new BookingWorker(f.ports, f.ports, f.ports, calendar, f.ports, f.ports, f.clock);
    }

    void agreed() {
        f.sent();
        f.responses.respond(f.accept("accept", 2, 1));
    }

    Booking booking() {
        return f.ports.bookings.values().stream().reduce((a, b) -> b).orElseThrow();
    }

    ManualConfirmationCommand manual(UserId actor, String key, long version) {
        return new ManualConfirmationCommand(actor, new IdempotencyKey(key), f.interventionId, version, MANUAL_START,
                MANUAL_END, "phone", "Agreed by phone with the contractor.", "c-manual");
    }

    CancelInterventionCommand cancel(String key, long version, String reason) {
        return new CancelInterventionCommand(ADMIN_A, new IdempotencyKey(key), f.interventionId, version, reason,
                "The residents fixed it themselves.", "c-cancel");
    }

    @Test
    void aManualConfirmationBooksAtOnceAndLooksUpTheOutstandingRequest() {
        agreed();
        calendar.creates.add(new CalendarReply.Unknown());
        worker.runDue();

        BookingCommandResult result = service.confirmManually(manual(ADMIN_A, "m1", 3));

        assertThat(result.state()).isEqualTo(InterventionState.BOOKED);
        assertThat(result.version()).isEqualTo(4);
        assertThat(result.bookingState()).contains(BookingState.CONFIRMED);
        assertThat(booking().source()).contains(BookingSource.MANUAL);
        assertThat(booking().action()).contains(BookingAction.ORPHAN_CHECK);
        assertThat(f.ports.outbox.getLast().event()).isInstanceOfSatisfying(BookingConfirmed.class, e -> {
            assertThat(e.source()).isEqualTo(BookingSource.MANUAL);
            assertThat(e.window().start()).isEqualTo(java.time.Instant.parse(MANUAL_START));
        });

        calendar.finds.add(new CalendarReply.Booked("SIM-BK-3"));
        f.clock.advance(Duration.ofSeconds(1));
        assertThat(worker.runDue()).as("not before the reconciliation that was due").isZero();
        f.clock.advance(Duration.ofSeconds(4));
        worker.runDue();
        assertThat(booking().state()).as("never two confirmed bookings").isEqualTo(BookingState.DISCREPANCY);
        assertThat(booking().reasonCode()).contains(Booking.FOUND_AFTER_MANUAL);
        assertThat(calendar.count("create")).isEqualTo(1);
    }

    @Test
    void aManualConfirmationChecksWhoThenFieldsThenStateAndVersion() {
        assertThatThrownBy(() -> service.confirmManually(manual(RESIDENT_A1, "m0", 9)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.confirmManually(new ManualConfirmationCommand(ADMIN_A, new IdempotencyKey("m1"),
                f.interventionId, 9, null, null, "fax", "", "c")))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .extracting(FieldViolation::field)
                        .containsExactlyInAnyOrder("window.start", "window.end", "source", "reason"));
        assertThatThrownBy(() -> service.confirmManually(manual(ADMIN_A, "m2", 1)))
                .as("nothing agreed yet").isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("awaiting_approval"));
        agreed();
        assertThatThrownBy(() -> service.confirmManually(manual(ADMIN_A, "m3", 2)))
                .isInstanceOf(VersionConflictException.class);
        worker.runDue();
        assertThatThrownBy(() -> service.confirmManually(manual(ADMIN_A, "m4", 4)))
                .as("already booked by the calendar").isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("booked"));
    }

    @Test
    void anotherRequestAfterARejectionIsANewBookingForTheSameWindow() {
        agreed();
        calendar.creates.add(new CalendarReply.Conflict());
        worker.runDue();
        Booking rejected = booking();

        BookingCommandResult again = service.requestAgain(new RequestBookingCommand(ADMIN_A, new IdempotencyKey("r1"),
                f.interventionId, 3, "c-again"));

        assertThat(again.bookingState()).contains(BookingState.REQUESTED);
        assertThat(again.version()).isEqualTo(4);
        assertThat(f.ports.bookings).hasSize(2);
        assertThat(booking().id()).isNotEqualTo(rejected.id());
        assertThat(booking().window()).isEqualTo(rejected.window());
        worker.runDue();
        assertThat(f.stored().state()).isEqualTo(InterventionState.BOOKED);
        assertThatThrownBy(() -> service.requestAgain(new RequestBookingCommand(ADMIN_A, new IdempotencyKey("r2"),
                f.interventionId, 5, "c"))).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void aCancellationBeforeAgreementIsImmediateWithItsEvent() {
        f.sent();

        BookingCommandResult result = service.cancel(cancel("c1", 2, "no_longer_needed"));

        assertThat(result.state()).isEqualTo(InterventionState.CANCELLED);
        assertThat(f.stored().cancelReason()).contains(CancelReason.NO_LONGER_NEEDED);
        assertThat(f.stored().cancelNote()).contains("The residents fixed it themselves.");
        assertThat(f.ports.outbox.getLast().event()).isInstanceOfSatisfying(InterventionCancelled.class, e -> {
            assertThat(e.reasonCode()).isEqualTo(CancelReason.NO_LONGER_NEEDED);
            assertThat(e.toString()).doesNotContain("residents fixed");
        });
        assertThat(f.ports.findActiveForIssue(f.issueId)).isEmpty();
    }

    @Test
    void aCancellationWithACalendarBookingStaysPendingUntilTheCalendarCancelsIt() {
        agreed();
        worker.runDue();

        BookingCommandResult result = service.cancel(cancel("c1", 4, "rescheduling"));

        assertThat(result.state()).isEqualTo(InterventionState.CANCELLATION_PENDING);
        assertThat(f.ports.outbox.getLast().event()).isInstanceOf(InterventionCancellationPending.class);
        calendar.cancels.add(new CalendarReply.Unavailable());
        f.clock.advance(Duration.ofSeconds(1));
        worker.runDue();
        assertThat(f.stored().state()).as("still pending").isEqualTo(InterventionState.CANCELLATION_PENDING);

        f.clock.advance(Duration.ofSeconds(5));
        worker.runDue();

        assertThat(f.stored().state()).isEqualTo(InterventionState.CANCELLED);
        assertThat(booking().state()).isEqualTo(BookingState.CANCELLED);
        assertThat(calendar.calls).filteredOn(c -> c.startsWith("cancel")).containsOnly("cancel SIM-BK-1");
        assertThat(f.ports.outbox.getLast().event()).isInstanceOfSatisfying(InterventionCancelled.class,
                e -> assertThat(e.reasonCode()).isEqualTo(CancelReason.RESCHEDULING));
    }

    @Test
    void aCancellationWhileTheRequestIsUncertainLooksItUpAndCancelsWhatItFinds() {
        agreed();
        calendar.creates.add(new CalendarReply.Unknown());
        worker.runDue();
        service.cancel(cancel("c1", 3, "other"));
        calendar.finds.add(new CalendarReply.Booked("SIM-BK-8"));

        f.clock.advance(Duration.ofSeconds(1));
        assertThat(worker.runDue()).as("not before the reconciliation that was due").isZero();
        f.clock.advance(Duration.ofSeconds(4));
        worker.runDue();

        assertThat(calendar.calls).contains("cancel SIM-BK-8");
        assertThat(f.stored().state()).isEqualTo(InterventionState.CANCELLED);
        assertThat(calendar.count("create")).as("nothing re-sent").isEqualTo(1);
    }

    @Test
    void cancellationNeedsAKnownReasonAndAnAdministrator() {
        f.sent();
        assertThatThrownBy(() -> service.cancel(cancel("c1", 2, "bored")))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("reasonCode", "invalid")));
        assertThatThrownBy(() -> service.cancel(new CancelInterventionCommand(CONTRACTOR_LOGIN, new IdempotencyKey("c2"),
                f.interventionId, 2, "other", null, "c"))).isInstanceOf(AccessDeniedException.class);
        service.cancel(cancel("c3", 2, "other"));
        assertThatThrownBy(() -> service.cancel(cancel("c4", 3, "other")))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("cancelled"));
    }

    @Test
    void theSameKeyReplaysAndAReplayReChecksAccess() {
        f.sent();
        BookingCommandResult first = service.cancel(cancel("c1", 2, "other"));

        BookingCommandResult again = service.cancel(cancel("c1", 2, "other"));

        assertThat(again.replayed()).isTrue();
        assertThat(again.version()).isEqualTo(first.version());
        assertThat(f.ports.outbox.stream().filter(e -> e.event() instanceof InterventionCancelled)).hasSize(1);
        f.ports.memberships.put(MEMBERSHIP_ADMIN_A,
                membership(MEMBERSHIP_ADMIN_A, ADMIN_A, BUILDING_A, "administrator", false, 2));
        assertThatThrownBy(() -> service.cancel(cancel("c1", 2, "other"))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void reconciliationOnDemandAsksTheWorkerToCheckTheConfirmedBooking() {
        agreed();
        assertThatThrownBy(() -> service.reconcile(ADMIN_A, f.interventionId))
                .as("not booked yet").isInstanceOf(InvalidTransitionException.class);
        worker.runDue();
        calendar.finds.add(new CalendarReply.Absent());

        BookingCommandResult result = service.reconcile(ADMIN_A, f.interventionId);
        worker.runDue();

        assertThat(result.version()).as("a check moves no version").isEqualTo(4);
        assertThat(booking().state()).isEqualTo(BookingState.DISCREPANCY);
        assertThat(Optional.of(booking().reasonCode().orElseThrow())).contains(Booking.MISSING_AT_PROVIDER);
        assertThatThrownBy(() -> service.reconcile(ADMIN_B, f.interventionId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void approvingACounterproposalAlsoStartsTheBooking() {
        f.sent();
        f.responses.respond(f.counterpropose("k", 2, 1));
        f.approval.approve(f.approve(ADMIN_A, "approve-2", 3, 2));

        assertThat(booking().state()).isEqualTo(BookingState.REQUESTED);
        assertThat(booking().window().start()).isEqualTo(java.time.Instant.parse("2026-11-29T09:00:00Z"));
    }
}
