package pt.isep.bmc.dispatch.domain.booking;

import java.util.Objects;

/**
 * What a calendar call came back with, already classified as technical
 * design 4.5 asks: a {@code 2xx} counts as {@link Booked} only if it echoes
 * the request ID, has an external reference and the requested window.
 */
public sealed interface CalendarReply {

    /** A valid confirmation, or a lookup that found the confirmed booking. */
    record Booked(String externalRef) implements CalendarReply {

        public Booked {
            Objects.requireNonNull(externalRef, "externalRef");
            if (externalRef.isBlank()) {
                throw new IllegalArgumentException("an external reference is not blank");
            }
        }
    }

    /**
     * The calendar holds this request ID, confirmed, but not for the
     * requested window (or with no window that can be read): it exists, so
     * it is never treated as absent, and never as the agreed booking.
     */
    record Mismatched(String externalRef) implements CalendarReply {

        public Mismatched {
            Objects.requireNonNull(externalRef, "externalRef");
            if (externalRef.isBlank()) {
                throw new IllegalArgumentException("an external reference is not blank");
            }
        }
    }

    /** {@code 409}: the slot is taken. */
    record Conflict() implements CalendarReply {
    }

    /** {@code 422}: Dispatch sent something the contract does not allow. Never retried. */
    record Defect() implements CalendarReply {
    }

    /** {@code 503} or connection refused: nothing happened, try later. */
    record Unavailable() implements CalendarReply {
    }

    /** A timeout, a late answer or an invalid {@code 2xx}: the booking may or may not exist. */
    record Unknown() implements CalendarReply {
    }

    /** A lookup found no booking for the request ID. */
    record Absent() implements CalendarReply {
    }

    /** The booking is cancelled at the calendar: a cancellation succeeded, or a lookup found it cancelled. */
    record Cancelled() implements CalendarReply {
    }

    /** {@code 404} to a cancellation: the calendar does not know the reference. */
    record UnknownReference() implements CalendarReply {
    }
}
