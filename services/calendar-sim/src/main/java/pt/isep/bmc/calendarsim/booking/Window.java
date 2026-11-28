package pt.isep.bmc.calendarsim.booking;

import java.time.Instant;
import java.util.Objects;

/** A booked time window in UTC; the end is after the start. */
public record Window(Instant start, Instant end) {

    public Window {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("a window ends after it starts");
        }
    }

    public boolean overlaps(Window other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }
}
