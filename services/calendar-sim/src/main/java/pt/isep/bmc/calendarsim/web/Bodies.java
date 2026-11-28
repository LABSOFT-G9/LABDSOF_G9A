package pt.isep.bmc.calendarsim.web;

import java.time.format.DateTimeFormatter;
import java.util.List;
import pt.isep.bmc.calendarsim.booking.Booking;

/** JSON bodies of the simulator's API (technical design 4.5, annex A 1.5). */
final class Bodies {

    private Bodies() {
    }

    /** {@code POST /bookings}. Every field is checked by {@link BookingRequests}. */
    record CreateBooking(String requestId, String contractorRef, WindowBody window, String buildingCode, String summary) {

        /** Never prints the summary. */
        @Override
        public String toString() {
            return "CreateBooking[requestId=" + requestId + "]";
        }
    }

    /** RFC 3339 times; answers use UTC. */
    record WindowBody(String start, String end) {
    }

    /** One booking as every answer shows it. {@code externalRef} is null only in a scripted invalid answer. */
    record BookingBody(String requestId, String externalRef, String status, WindowBody window) {

        static BookingBody of(Booking booking) {
            return new BookingBody(booking.request().requestId(), booking.externalRef(), booking.status().value(),
                    new WindowBody(DateTimeFormatter.ISO_INSTANT.format(booking.request().window().start()),
                            DateTimeFormatter.ISO_INSTANT.format(booking.request().window().end())));
        }
    }

    /** {@code GET /bookings?requestId=}: one item, or none. */
    record BookingList(List<BookingBody> items) {
    }

    /** {@code POST /control/outcomes}. */
    record Script(String operation, List<ScriptedOutcome> outcomes) {
    }

    /** @param delayMs for {@code delay}: how long to wait, at most 60,000 */
    record ScriptedOutcome(String type, Long delayMs) {
    }
}
