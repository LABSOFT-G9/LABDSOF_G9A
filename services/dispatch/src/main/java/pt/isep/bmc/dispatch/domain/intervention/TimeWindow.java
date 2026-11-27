package pt.isep.bmc.dispatch.domain.intervention;

import java.time.Instant;
import java.util.Objects;

/** A proposed time window for the work, in UTC (US-06): it starts in the future and ends after it starts. */
public record TimeWindow(Instant start, Instant end) {

    public TimeWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("a window ends after it starts");
        }
    }
}
