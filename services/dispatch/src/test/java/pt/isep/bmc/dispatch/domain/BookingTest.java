package pt.isep.bmc.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.dispatch.domain.DomainFixtures.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.domain.booking.Booking;
import pt.isep.bmc.dispatch.domain.booking.BookingAction;
import pt.isep.bmc.dispatch.domain.booking.BookingId;
import pt.isep.bmc.dispatch.domain.booking.BookingSource;
import pt.isep.bmc.dispatch.domain.booking.BookingState;
import pt.isep.bmc.dispatch.domain.booking.BookingStep;
import pt.isep.bmc.dispatch.domain.booking.CalendarReply;
import pt.isep.bmc.dispatch.domain.booking.ManualConfirmation;
import pt.isep.bmc.dispatch.domain.booking.ManualSource;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;

/** US-08, technical design 4.5: a booking's outcomes, retries, reconciliation and cancellation. */
class BookingTest {

    static final TimeWindow WINDOW = new TimeWindow(NOW.plus(Duration.ofDays(3)), NOW.plus(Duration.ofDays(3)).plusSeconds(10_800));
    static final TimeWindow SOON = new TimeWindow(NOW.plus(Duration.ofHours(5)), NOW.plus(Duration.ofHours(8)));
    static final InterventionId INTERVENTION = new InterventionId(UUID.randomUUID());

    static Booking requested(TimeWindow window) {
        return Booking.request(new BookingId(UUID.randomUUID()), INTERVENTION, Optional.of("SIM-CTR-001"),
                BUILDING_A.toString(), "Plumbing intervention", window, NOW);
    }

    static BookingStep reply(Booking booking, CalendarReply reply) {
        Booking claimed = booking.claim(NOW, Duration.ofSeconds(60));
        return claimed.afterReply(claimed.action().orElseThrow(), reply, NOW);
    }

    static Booking confirmed() {
        return reply(requested(WINDOW), new CalendarReply.Booked("SIM-BK-1")).booking();
    }

    static ManualConfirmation manual() {
        return new ManualConfirmation(SOON, ManualSource.PHONE, "Agreed by phone with the contractor.", ADMIN_A, NOW);
    }

    @Test
    void aRequestIsSavedFirstAndDueAtOnce() {
        Booking booking = requested(WINDOW);

        assertThat(booking.state()).isEqualTo(BookingState.REQUESTED);
        assertThat(booking.action()).contains(BookingAction.CREATE);
        assertThat(booking.nextAttemptAt()).contains(NOW);
        assertThat(booking.version()).isEqualTo(1);
    }

    @Test
    void withoutACalendarReferenceOrWithAWindowThatStartedItFailsAtOnce() {
        Booking noRef = Booking.request(new BookingId(UUID.randomUUID()), INTERVENTION, Optional.empty(), "b",
                "Plumbing intervention", WINDOW, NOW);
        Booking started = Booking.request(new BookingId(UUID.randomUUID()), INTERVENTION, Optional.of("SIM-CTR-001"),
                "b", "Plumbing intervention", new TimeWindow(NOW.minusSeconds(60), NOW.plusSeconds(3600)), NOW);

        assertThat(noRef.state()).isEqualTo(BookingState.FAILED);
        assertThat(noRef.reasonCode()).contains(Booking.NO_CALENDAR);
        assertThat(noRef.action()).isEmpty();
        assertThat(started.reasonCode()).contains(Booking.WINDOW_PASSED);
    }

    @Test
    void aValidConfirmationIsConfirmedByTheCalendarAndCheckedAgain24HoursBefore() {
        BookingStep step = reply(requested(WINDOW), new CalendarReply.Booked("SIM-BK-1"));

        assertThat(step.effect()).isEqualTo(BookingStep.Effect.CONFIRMED);
        Booking booked = step.booking();
        assertThat(booked.state()).isEqualTo(BookingState.CONFIRMED);
        assertThat(booked.source()).contains(BookingSource.CALENDAR);
        assertThat(booked.externalRef()).contains("SIM-BK-1");
        assertThat(booked.action()).contains(BookingAction.VERIFY);
        assertThat(booked.nextAttemptAt()).contains(WINDOW.start().minus(Duration.ofHours(24)));
        assertThat(reply(requested(SOON), new CalendarReply.Booked("SIM-BK-2")).booking().action())
                .as("a window within 24 hours was just checked").isEmpty();
    }

    @Test
    void aConflictIsRejectedAndADefectFailsWithoutRetry() {
        BookingStep conflict = reply(requested(WINDOW), new CalendarReply.Conflict());
        BookingStep defect = reply(requested(WINDOW), new CalendarReply.Defect());

        assertThat(conflict.booking().state()).isEqualTo(BookingState.REJECTED);
        assertThat(conflict.booking().reasonCode()).contains(Booking.SLOT_CONFLICT);
        assertThat(conflict.effect()).isEqualTo(BookingStep.Effect.STATE_CHANGED);
        assertThat(defect.booking().state()).isEqualTo(BookingState.FAILED);
        assertThat(defect.booking().reasonCode()).contains(Booking.CONTRACT_DEFECT);
        assertThat(defect.booking().action()).isEmpty();
    }

    @Test
    void unavailableIsRetriedAfter5_10_20And40SecondsThenFails() {
        Booking booking = requested(WINDOW);
        Instant at = NOW;
        for (int wait : new int[] {5, 10, 20, 40}) {
            Booking claimed = booking.claim(at, Duration.ofSeconds(60));
            BookingStep step = claimed.afterReply(BookingAction.CREATE, new CalendarReply.Unavailable(), at);
            assertThat(step.booking().state()).isEqualTo(BookingState.REQUESTED);
            assertThat(step.effect()).isEqualTo(BookingStep.Effect.NONE);
            assertThat(step.booking().nextAttemptAt()).contains(at.plusSeconds(wait));
            booking = step.booking();
            at = at.plusSeconds(wait);
        }
        BookingStep fifth = booking.claim(at, Duration.ofSeconds(60))
                .afterReply(BookingAction.CREATE, new CalendarReply.Unavailable(), at);

        assertThat(fifth.booking().attempts()).isZero();
        assertThat(fifth.booking().state()).isEqualTo(BookingState.FAILED);
        assertThat(fifth.booking().reasonCode()).contains(Booking.RETRIES_EXHAUSTED);
        assertThat(fifth.effect()).isEqualTo(BookingStep.Effect.STATE_CHANGED);
    }

    @Test
    void aTimeoutIsUncertainAndReconciledBeforeAnyResend() {
        BookingStep timeout = reply(requested(WINDOW), new CalendarReply.Unknown());

        assertThat(timeout.booking().state()).isEqualTo(BookingState.UNCERTAIN);
        assertThat(timeout.booking().action()).contains(BookingAction.RECONCILE);
        assertThat(timeout.effect()).isEqualTo(BookingStep.Effect.STATE_CHANGED);

        BookingStep found = reply(timeout.booking(), new CalendarReply.Booked("SIM-BK-9"));
        assertThat(found.booking().state()).isEqualTo(BookingState.CONFIRMED);
        assertThat(found.effect()).isEqualTo(BookingStep.Effect.CONFIRMED);

        BookingStep stillUnknown = reply(timeout.booking(), new CalendarReply.Unavailable());
        assertThat(stillUnknown.booking().state()).as("unavailable keeps it uncertain").isEqualTo(BookingState.UNCERTAIN);
        assertThat(stillUnknown.booking().action()).contains(BookingAction.RECONCILE);
    }

    @Test
    void aConfirmedBookingThatIsGoneIsADiscrepancyNeverRecreated() {
        Booking booked = confirmed();

        BookingStep missing = reply(booked, new CalendarReply.Absent());
        BookingStep present = reply(booked, new CalendarReply.Booked("SIM-BK-1"));

        assertThat(missing.booking().state()).isEqualTo(BookingState.DISCREPANCY);
        assertThat(missing.booking().reasonCode()).contains(Booking.MISSING_AT_PROVIDER);
        assertThat(missing.booking().action()).as("never re-sent").isEmpty();
        assertThat(missing.effect()).isEqualTo(BookingStep.Effect.STATE_CHANGED);
        assertThat(present.booking().state()).isEqualTo(BookingState.CONFIRMED);
        assertThat(present.booking().lastReconciledAt()).contains(NOW);
        assertThat(present.booking().action()).isEmpty();
    }

    @Test
    void aManualConfirmationWinsAndAnOutstandingRequestIsLookedUpOnce() {
        Booking uncertain = reply(requested(WINDOW), new CalendarReply.Unknown()).booking();

        Booking manual = uncertain.confirmManually(manual(), NOW);

        assertThat(manual.state()).isEqualTo(BookingState.CONFIRMED);
        assertThat(manual.source()).contains(BookingSource.MANUAL);
        assertThat(manual.bookedWindow()).isEqualTo(SOON);
        assertThat(manual.window()).as("the request as sent").isEqualTo(WINDOW);
        assertThat(manual.action()).contains(BookingAction.ORPHAN_CHECK);

        BookingStep found = reply(manual, new CalendarReply.Booked("SIM-BK-7"));
        assertThat(found.booking().state()).isEqualTo(BookingState.DISCREPANCY);
        assertThat(found.booking().reasonCode()).contains(Booking.FOUND_AFTER_MANUAL);
        assertThat(found.booking().externalRef()).contains("SIM-BK-7");
        assertThat(reply(manual, new CalendarReply.Absent()).booking().state()).isEqualTo(BookingState.CONFIRMED);

        Booking rejected = reply(requested(WINDOW), new CalendarReply.Conflict()).booking();
        assertThat(rejected.confirmManually(manual(), NOW).action()).as("nothing outstanding").isEmpty();
        assertThatThrownBy(() -> confirmed().confirmManually(manual(), NOW))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void cancellationCallsTheCalendarOnlyWhenABookingExistsOrMayExist() {
        assertThat(requested(WINDOW).startCancellation(NOW).needsCalendar()).isTrue();
        assertThat(reply(requested(WINDOW), new CalendarReply.Unknown()).booking().startCancellation(NOW)
                .needsCalendar()).isTrue();
        Booking.CancellationStart booked = confirmed().startCancellation(NOW);
        assertThat(booked.needsCalendar()).isTrue();
        assertThat(booked.booking().action()).contains(BookingAction.CANCEL);

        Booking.CancellationStart manualOnly = reply(requested(WINDOW), new CalendarReply.Conflict()).booking()
                .confirmManually(manual(), NOW).startCancellation(NOW);
        assertThat(manualOnly.needsCalendar()).isFalse();
        assertThat(manualOnly.booking().state()).isEqualTo(BookingState.CANCELLED);
    }

    @Test
    void aCancellationStaysPendingUntilTheCalendarAnswersAndHasNoAttemptLimit() {
        Booking cancelling = confirmed().startCancellation(NOW).booking();
        for (int i = 0; i < 8; i++) {
            BookingStep down = reply(cancelling, new CalendarReply.Unavailable());
            assertThat(down.booking().action()).contains(BookingAction.CANCEL);
            assertThat(down.booking().state()).isEqualTo(BookingState.CONFIRMED);
            cancelling = down.booking();
        }

        BookingStep done = reply(cancelling, new CalendarReply.Cancelled());

        assertThat(done.booking().state()).isEqualTo(BookingState.CANCELLED);
        assertThat(done.effect()).isEqualTo(BookingStep.Effect.CANCELLED);
        assertThat(reply(confirmed().startCancellation(NOW).booking(), new CalendarReply.UnknownReference()).effect())
                .as("unknown at the calendar: nothing left to cancel").isEqualTo(BookingStep.Effect.CANCELLED);
    }

    @Test
    void onlyAConfirmedCalendarBookingCanBeCheckedOnDemand() {
        assertThat(confirmed().requestVerification(NOW).action()).contains(BookingAction.VERIFY);
        assertThatThrownBy(() -> requested(WINDOW).requestVerification(NOW))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("requested"));
    }
}
