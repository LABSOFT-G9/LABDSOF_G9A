package pt.isep.bmc.messaging.outbox;

import java.time.Duration;

/**
 * Pause after a failed publish: the initial value, doubled after each failure
 * up to the maximum, and back to the initial value after a success. There is no
 * attempt limit (technical design 4.6).
 */
final class Backoff {

    private final Duration initial;
    private final Duration max;
    private Duration next;

    Backoff(Duration initial, Duration max) {
        if (initial.isZero() || initial.isNegative() || max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("backoff needs 0 < initial <= max");
        }
        this.initial = initial;
        this.max = max;
        this.next = initial;
    }

    /** The pause to take now; the one after it is twice as long, up to the maximum. */
    Duration next() {
        Duration current = next;
        Duration doubled = next.multipliedBy(2);
        next = doubled.compareTo(max) > 0 ? max : doubled;
        return current;
    }

    void reset() {
        next = initial;
    }
}
