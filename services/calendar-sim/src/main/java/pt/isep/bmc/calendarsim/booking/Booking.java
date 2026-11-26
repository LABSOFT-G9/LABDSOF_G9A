package pt.isep.bmc.calendarsim.booking;

import java.time.Instant;
import java.util.Objects;

/** A booking held by the simulator, in memory. */
public record Booking(BookingRequest request, String externalRef, Status status, Instant createdAt) {

    public enum Status {
        CONFIRMED("confirmed"),
        CANCELLED("cancelled");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public Booking {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public Booking cancelled() {
        return new Booking(request, externalRef, Status.CANCELLED, createdAt);
    }
}
