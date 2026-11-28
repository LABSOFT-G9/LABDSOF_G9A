package pt.isep.bmc.dispatch.domain.booking;

import java.time.Instant;
import java.util.Objects;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * A booking an administrator confirmed by hand (US-08): the window agreed,
 * how it was agreed and why. The reason is free text for the building's administrators; it never
 * goes into an event or a log.
 */
public record ManualConfirmation(
        TimeWindow window, ManualSource source, String reason, UserId confirmedBy, Instant confirmedAt) {

    public ManualConfirmation {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(confirmedBy, "confirmedBy");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
    }

    @Override
    public String toString() {
        return "ManualConfirmation[source=" + source + "]";
    }
}
