package pt.isep.bmc.dispatch.application.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import pt.isep.bmc.dispatch.application.port.in.RunBookingWorkUseCase;
import pt.isep.bmc.dispatch.application.port.out.BookingRepository;
import pt.isep.bmc.dispatch.application.port.out.CalendarPort;
import pt.isep.bmc.dispatch.application.port.out.EventOutbox;
import pt.isep.bmc.dispatch.application.port.out.IdGenerator;
import pt.isep.bmc.dispatch.application.port.out.InterventionRepository;
import pt.isep.bmc.dispatch.application.port.out.OutboxEvent;
import pt.isep.bmc.dispatch.application.port.out.TransactionRunner;
import pt.isep.bmc.dispatch.domain.booking.Booking;
import pt.isep.bmc.dispatch.domain.booking.BookingAction;
import pt.isep.bmc.dispatch.domain.booking.BookingSource;
import pt.isep.bmc.dispatch.domain.booking.BookingState;
import pt.isep.bmc.dispatch.domain.booking.BookingStep;
import pt.isep.bmc.dispatch.domain.booking.CalendarReply;
import pt.isep.bmc.dispatch.domain.event.BookingConfirmed;
import pt.isep.bmc.dispatch.domain.event.BookingStateChanged;
import pt.isep.bmc.dispatch.domain.event.InterventionCancelled;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;

/**
 * Makes the calls each booking needs (US-08, technical design 4.5 and 4.6).
 *
 * <p>Each booking is handled in three steps, so the calendar is never called
 * inside a database transaction:
 * <ol>
 *   <li>claim: lock the booking that is due soonest, count the attempt and
 *       push its due time out by the lease, then commit;</li>
 *   <li>call the calendar;</li>
 *   <li>apply: lock the intervention, then the booking, and apply the reply
 *       only if the booking is still the claimed version. A booking changed
 *       meanwhile (a manual confirmation, a cancellation) keeps the new
 *       state, whose own lookup finds anything this call created.</li>
 * </ol>
 * Everything is in the database, so a restart resumes where it stopped: a
 * lease that expires makes the booking due again, and it is reconciled or
 * re-sent with the same key.
 */
public final class BookingWorker implements RunBookingWorkUseCase {

    /** How long a claimed booking is not due again: longer than any call (5 s timeout, a lookup and a create). */
    static final Duration LEASE = Duration.ofSeconds(60);
    static final int BATCH = 20;

    private final TransactionRunner transactions;
    private final BookingRepository bookings;
    private final InterventionRepository interventions;
    private final CalendarPort calendar;
    private final EventOutbox outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public BookingWorker(
            TransactionRunner transactions,
            BookingRepository bookings,
            InterventionRepository interventions,
            CalendarPort calendar,
            EventOutbox outbox,
            IdGenerator ids,
            Clock clock) {
        this.transactions = transactions;
        this.bookings = bookings;
        this.interventions = interventions;
        this.calendar = calendar;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public int runDue() {
        int handled = 0;
        while (handled < BATCH) {
            Optional<Booking> claimed = transactions.inTransaction(() -> {
                Instant now = now();
                return bookings.lockNextDue(now).map(due -> {
                    Booking taken = due.claim(now, LEASE);
                    bookings.update(taken);
                    return taken;
                });
            });
            if (claimed.isEmpty()) {
                return handled;
            }
            handle(claimed.get());
            handled++;
        }
        return handled;
    }

    private void handle(Booking claimed) {
        BookingAction action = claimed.action().orElseThrow();
        Call call = call(claimed, action);
        transactions.inTransaction(() -> {
            apply(claimed, action, call);
            return null;
        });
    }

    /** The reply to apply, and the booking it applies to (with a reference a lookup just found). */
    private record Call(Booking booking, BookingAction appliedAs, CalendarReply reply) {
    }

    private Call call(Booking claimed, BookingAction action) {
        return switch (action) {
            case CREATE -> new Call(claimed, action, logged(claimed, action, calendar.create(bookingCall(claimed))));
            case RECONCILE -> {
                CalendarReply found = logged(claimed, action, calendar.find(claimed.id()));
                if (found instanceof CalendarReply.Absent) {
                    // Not booked: re-send with the same key (technical design 4.5).
                    yield new Call(claimed, BookingAction.CREATE,
                            logged(claimed, BookingAction.CREATE, calendar.create(bookingCall(claimed))));
                }
                yield new Call(claimed, action, found);
            }
            case VERIFY, ORPHAN_CHECK -> new Call(claimed, action, logged(claimed, action, calendar.find(claimed.id())));
            case CANCEL -> {
                Optional<String> ref = claimed.externalRef();
                if (ref.isEmpty()) {
                    CalendarReply found = logged(claimed, BookingAction.RECONCILE, calendar.find(claimed.id()));
                    if (!(found instanceof CalendarReply.Booked booked)) {
                        yield new Call(claimed, action, found);
                    }
                    ref = Optional.of(booked.externalRef());
                }
                yield new Call(claimed, action, logged(claimed, action, calendar.cancel(ref.get(), claimed.id())));
            }
        };
    }

    private void apply(Booking claimed, BookingAction action, Call call) {
        Instant now = now();
        Optional<Intervention> intervention = interventions.findForUpdate(claimed.interventionId());
        Optional<Booking> current = bookings.findForUpdate(claimed.id());
        if (intervention.isEmpty() || current.isEmpty() || current.get().version() != claimed.version()) {
            return;
        }
        BookingStep step = current.get().afterReply(call.appliedAs(), call.reply(), now);
        bookings.update(step.booking());
        Intervention before = intervention.get();
        switch (step.effect()) {
            case CONFIRMED -> before.booked(now).ifPresent(booked -> {
                interventions.update(booked);
                append(new BookingConfirmed(booked.id().value(), booked.version(), booked.buildingId(), now,
                        booked.issueId(), step.booking().bookedWindow(), BookingSource.CALENDAR, booked.state()));
            });
            case STATE_CHANGED -> {
                BookingState state = step.booking().state();
                append(new BookingStateChanged(before.id().value(), before.version(), before.buildingId(), now,
                        before.issueId(), state, before.state()));
            }
            case CANCELLED -> before.cancellationCompleted(now).ifPresent(cancelled -> {
                interventions.update(cancelled);
                append(new InterventionCancelled(cancelled.id().value(), cancelled.version(), cancelled.buildingId(),
                        now, cancelled.issueId(), cancelled.cancelReason().orElseThrow(), cancelled.state()));
            });
            case NONE -> {
            }
        }
    }

    private CalendarReply logged(Booking booking, BookingAction action, CalendarReply reply) {
        transactions.inTransaction(() -> {
            bookings.logCall(booking.id(), action, outcome(reply), now());
            return null;
        });
        return reply;
    }

    private void append(pt.isep.bmc.dispatch.domain.event.DomainEvent event) {
        outbox.append(new OutboxEvent(ids.newId(), event, "booking-worker"));
    }

    private static CalendarPort.BookingCall bookingCall(Booking booking) {
        return new CalendarPort.BookingCall(booking.id(), booking.contractorRef(), booking.window(),
                booking.buildingCode(), booking.summary());
    }

    static String outcome(CalendarReply reply) {
        return switch (reply) {
            case CalendarReply.Booked b -> "booked";
            case CalendarReply.Conflict c -> "conflict";
            case CalendarReply.Defect d -> "defect";
            case CalendarReply.Unavailable u -> "unavailable";
            case CalendarReply.Unknown u -> "unknown";
            case CalendarReply.Absent a -> "absent";
            case CalendarReply.Cancelled c -> "cancelled";
            case CalendarReply.UnknownReference r -> "unknown_reference";
        };
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
