package pt.isep.bmc.dispatch.domain.booking;

import java.util.Objects;

/**
 * A booking after a calendar reply, and what it means for the intervention.
 *
 * @param effect {@link Effect#CONFIRMED}: the intervention is booked; {@link Effect#STATE_CHANGED}: Issue hears the
 *               new booking state; {@link Effect#CANCELLED}: the pending cancellation is complete
 */
public record BookingStep(Booking booking, Effect effect) {

    public enum Effect {
        NONE,
        CONFIRMED,
        STATE_CHANGED,
        CANCELLED
    }

    public BookingStep {
        Objects.requireNonNull(booking, "booking");
        Objects.requireNonNull(effect, "effect");
    }
}
