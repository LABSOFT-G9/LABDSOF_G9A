package pt.isep.bmc.calendarsim.booking;

import java.time.Duration;
import java.util.Objects;

/**
 * What the simulator does with the next call of one operation (technical
 * design 4.5). Scripted through {@code /control}; without a script every
 * call is {@link Type#OK}.
 *
 * @param delay how long to wait before answering, for {@link Type#DELAY}
 */
public record Outcome(Type type, Duration delay) {

    public enum Type {
        /** Behave normally: create, find or cancel; a real slot conflict still answers {@code 409}. */
        OK("ok"),
        /** {@code 409} slot conflict, whatever the calendar holds. */
        CONFLICT("conflict"),
        /** {@code 503}, nothing done. */
        UNAVAILABLE("unavailable"),
        /** A {@code 2xx} answer Dispatch must not trust: no {@code externalRef}, nothing booked. */
        INVALID("invalid"),
        /** Wait {@code delay}, then behave normally. */
        DELAY("delay"),
        /** Create the booking, then hang longer than Dispatch's timeout before answering. */
        TIMEOUT_AFTER_CREATE("timeout_after_create");

        private final String value;

        Type(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Type fromValue(String value) {
            for (Type type : values()) {
                if (type.value.equals(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("unknown outcome");
        }
    }

    public static final Outcome OK = new Outcome(Type.OK, Duration.ZERO);

    public Outcome {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(delay, "delay");
    }
}
