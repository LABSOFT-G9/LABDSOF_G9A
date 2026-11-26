package pt.isep.bmc.dispatch.application.port.out;

import java.util.Objects;
import pt.isep.bmc.dispatch.domain.booking.BookingId;
import pt.isep.bmc.dispatch.domain.booking.CalendarReply;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;

/**
 * The external calendar (ADR-006, technical design 4.5), provider-neutral.
 * Every call answers within the adapter's timeout and never throws: a
 * failure is a {@link CalendarReply}.
 */
public interface CalendarPort {

    /** {@code POST /bookings} with the booking ID as idempotency key. */
    CalendarReply create(BookingCall call);

    /** {@code GET /bookings?requestId=}: {@code Booked}, {@code Cancelled}, {@code Absent}, or a failure. */
    CalendarReply find(BookingId id);

    /** Cancels a booking the calendar confirmed: {@code Cancelled}, {@code UnknownReference}, or a failure. */
    CalendarReply cancel(String externalRef, BookingId id);

    /** What the calendar is told: no names, addresses or report text. */
    record BookingCall(BookingId id, String contractorRef, TimeWindow window, String buildingCode, String summary) {

        public BookingCall {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(contractorRef, "contractorRef");
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(buildingCode, "buildingCode");
            Objects.requireNonNull(summary, "summary");
        }
    }
}
