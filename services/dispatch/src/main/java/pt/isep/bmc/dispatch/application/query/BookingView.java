package pt.isep.bmc.dispatch.application.query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import pt.isep.bmc.dispatch.domain.booking.BookingAction;
import pt.isep.bmc.dispatch.domain.booking.BookingId;
import pt.isep.bmc.dispatch.domain.booking.BookingSource;
import pt.isep.bmc.dispatch.domain.booking.BookingState;
import pt.isep.bmc.dispatch.domain.booking.ManualSource;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;

/**
 * A booking as the building's administrators see it (US-08): its state and
 * source kept apart from the intervention's, the reason of a failure, and
 * every calendar call.
 *
 * @param requestedWindow the window asked of the calendar
 * @param bookedWindow    the window booked: the manual one for a manual confirmation
 * @param pendingAction   what the worker will do next, if anything
 */
public record BookingView(
        BookingId bookingId,
        BookingState state,
        Optional<BookingSource> source,
        TimeWindow requestedWindow,
        TimeWindow bookedWindow,
        Optional<String> externalRef,
        Optional<String> reasonCode,
        Optional<BookingAction> pendingAction,
        Optional<Instant> lastReconciledAt,
        Optional<Manual> manual,
        List<Call> calls,
        Instant createdAt,
        Instant updatedAt) {

    public BookingView {
        calls = List.copyOf(calls);
    }

    /** @param reason the administrator's text; for administrators only */
    public record Manual(ManualSource source, String reason, Instant confirmedAt) {

        @Override
        public String toString() {
            return "Manual[source=" + source + "]";
        }
    }

    public record Call(BookingAction action, String outcome, Instant at) {
    }
}
