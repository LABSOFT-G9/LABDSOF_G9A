package pt.isep.bmc.dispatch.domain.booking;

import java.util.Objects;
import java.util.UUID;

/** A booking's ID, also the calendar's request ID and idempotency key (technical design 4.5). */
public record BookingId(UUID value) {

    public BookingId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
