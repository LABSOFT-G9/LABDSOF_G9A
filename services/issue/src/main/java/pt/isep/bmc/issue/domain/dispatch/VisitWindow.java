package pt.isep.bmc.issue.domain.dispatch;

import java.time.Instant;
import java.util.Objects;

/** The booked window of a visit, from {@code BookingConfirmed}. */
public record VisitWindow(Instant start, Instant end) {

    public VisitWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("a window ends after it starts");
        }
    }
}
