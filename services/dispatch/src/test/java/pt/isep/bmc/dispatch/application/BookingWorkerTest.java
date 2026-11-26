package pt.isep.bmc.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.application.ApplicationFixtures.*;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.application.command.BookingWorker;
import pt.isep.bmc.dispatch.domain.booking.Booking;
import pt.isep.bmc.dispatch.domain.booking.BookingSource;
import pt.isep.bmc.dispatch.domain.booking.BookingState;
import pt.isep.bmc.dispatch.domain.booking.CalendarReply;
import pt.isep.bmc.dispatch.domain.event.BookingConfirmed;
import pt.isep.bmc.dispatch.domain.event.BookingStateChanged;
import pt.isep.bmc.dispatch.domain.event.DomainEvent;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;

/** US-08: the worker books the agreed window once, whatever the calendar does. */
class BookingWorkerTest {

    AgreementFixtures f = new AgreementFixtures();
    FakeCalendar calendar = new FakeCalendar();
    BookingWorker worker;

    @BeforeEach
    void agreed() {
        f.sent();
        f.responses.respond(f.accept("accept", 2, 1));
        f.ports.outbox.clear();
        worker = new BookingWorker(f.ports, f.ports, f.ports, calendar, f.ports, f.ports, f.clock);
    }

    Booking booking() {
        return f.ports.bookings.values().stream().reduce((a, b) -> b).orElseThrow();
    }

    DomainEvent lastEvent() {
        return f.ports.outbox.getLast().event();
    }

    void later(int seconds) {
        f.clock.advance(Duration.ofSeconds(seconds));
    }

    @Test
    void acceptanceSavesTheRequestBeforeAnyCall() {
        assertThat(booking().state()).isEqualTo(BookingState.REQUESTED);
        assertThat(booking().contractorRef()).isEqualTo(PLUMBER_CALENDAR_REF);
        assertThat(booking().summary()).isEqualTo("Plumbing intervention");
        assertThat(booking().buildingCode()).isEqualTo(BUILDING_A.toString());
        assertThat(calendar.calls).isEmpty();
    }

    @Test
    void aConfirmationBooksTheInterventionAndWritesBookingConfirmed() {
        assertThat(worker.runDue()).isEqualTo(1);

        assertThat(booking().state()).isEqualTo(BookingState.CONFIRMED);
        assertThat(f.stored().state()).isEqualTo(InterventionState.BOOKED);
        assertThat(f.stored().version()).isEqualTo(4);
        assertThat(lastEvent()).isInstanceOfSatisfying(BookingConfirmed.class, e -> {
            assertThat(e.source()).isEqualTo(BookingSource.CALENDAR);
            assertThat(e.window()).isEqualTo(booking().window());
            assertThat(e.aggregateVersion()).isEqualTo(4);
            assertThat(e.interventionState()).isEqualTo(InterventionState.BOOKED);
        });
        assertThat(f.ports.calls).containsExactly("create:booked");
        assertThat(worker.runDue()).as("nothing due now").isZero();
    }

    @Test
    void aTimeoutAfterTheBookingWasMadeIsReconciledAndNotSentAgain() {
        calendar.creates.add(new CalendarReply.Unknown());
        calendar.finds.add(new CalendarReply.Booked("SIM-BK-55821"));

        worker.runDue();
        assertThat(booking().state()).isEqualTo(BookingState.UNCERTAIN);
        assertThat(lastEvent()).isInstanceOfSatisfying(BookingStateChanged.class,
                e -> assertThat(e.bookingState()).isEqualTo(BookingState.UNCERTAIN));
        later(5);
        worker.runDue();

        assertThat(booking().state()).isEqualTo(BookingState.CONFIRMED);
        assertThat(booking().externalRef()).contains("SIM-BK-55821");
        assertThat(calendar.count("create")).as("one booking").isEqualTo(1);
        assertThat(f.stored().state()).isEqualTo(InterventionState.BOOKED);
    }

    @Test
    void aTimeoutBeforeTheBookingWasMadeIsResentWithTheSameKey() {
        calendar.creates.add(new CalendarReply.Unknown());
        calendar.finds.add(new CalendarReply.Absent());

        worker.runDue();
        later(5);
        worker.runDue();

        assertThat(calendar.calls).hasSize(3);
        assertThat(calendar.calls.get(0)).isEqualTo(calendar.calls.get(2)).startsWith("create " + booking().id());
        assertThat(booking().state()).isEqualTo(BookingState.CONFIRMED);
    }

    @Test
    void aSlotConflictIsRejectedWithItsReasonAndNothingIsBooked() {
        calendar.creates.add(new CalendarReply.Conflict());

        worker.runDue();

        assertThat(booking().state()).isEqualTo(BookingState.REJECTED);
        assertThat(booking().reasonCode()).contains(Booking.SLOT_CONFLICT);
        assertThat(f.stored().state()).isEqualTo(InterventionState.SCHEDULING_PENDING);
        assertThat(lastEvent()).isInstanceOfSatisfying(BookingStateChanged.class, e -> {
            assertThat(e.bookingState()).isEqualTo(BookingState.REJECTED);
            assertThat(e.aggregateVersion()).as("the intervention did not change").isEqualTo(3);
        });
    }

    @Test
    void anUnavailableCalendarIsTriedFiveTimesThenTheBookingFails() {
        for (int i = 0; i < 5; i++) {
            calendar.creates.add(new CalendarReply.Unavailable());
        }
        int[] waits = {5, 10, 20, 40};
        worker.runDue();
        for (int wait : waits) {
            later(wait - 1);
            assertThat(worker.runDue()).as("not due yet").isZero();
            later(1);
            assertThat(worker.runDue()).isEqualTo(1);
        }

        assertThat(calendar.count("create")).isEqualTo(5);
        assertThat(booking().state()).isEqualTo(BookingState.FAILED);
        assertThat(booking().reasonCode()).contains(Booking.RETRIES_EXHAUSTED);
        assertThat(f.stored().state()).as("needs manual action").isEqualTo(InterventionState.SCHEDULING_PENDING);
    }

    @Test
    void aClaimedBookingIsNotDueAgainUntilItsLeaseEnds() {
        Booking claimed = booking().claim(f.clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                Duration.ofSeconds(60));
        f.ports.bookings.put(claimed.id(), claimed);

        assertThat(worker.runDue()).isZero();
        later(60);
        assertThat(worker.runDue()).as("after a crash, the lease ends and the call is made again").isEqualTo(1);
    }

    @Test
    void aReplyForABookingChangedDuringTheCallIsDiscarded() {
        FakeCalendar slow = new FakeCalendar() {
            @Override
            public CalendarReply create(BookingCall call) {
                // A manual confirmation commits while the call is in flight.
                Booking current = booking();
                f.ports.bookings.put(current.id(), current.confirmManually(new pt.isep.bmc.dispatch.domain.booking
                        .ManualConfirmation(current.window(), pt.isep.bmc.dispatch.domain.booking.ManualSource.PHONE,
                        "Agreed by phone.", ADMIN_A, f.clock.instant()), f.clock.instant()));
                return new CalendarReply.Booked("SIM-BK-2");
            }
        };
        new BookingWorker(f.ports, f.ports, f.ports, slow, f.ports, f.ports, f.clock).runDue();

        assertThat(booking().source()).contains(BookingSource.MANUAL);
        assertThat(booking().externalRef()).as("the late reply is not applied").isEmpty();
    }

    @Test
    void aBookingMissingBeforeItsWindowIsADiscrepancy() {
        worker.runDue();
        calendar.finds.add(new CalendarReply.Absent());
        f.clock.advance(Duration.between(f.clock.instant(), booking().nextAttemptAt().orElseThrow()));

        worker.runDue();

        assertThat(booking().state()).isEqualTo(BookingState.DISCREPANCY);
        assertThat(booking().reasonCode()).contains(Booking.MISSING_AT_PROVIDER);
        assertThat(calendar.count("create")).as("never recreated").isEqualTo(1);
        assertThat(lastEvent()).isInstanceOfSatisfying(BookingStateChanged.class,
                e -> assertThat(e.bookingState()).isEqualTo(BookingState.DISCREPANCY));
    }
}
