package pt.isep.bmc.dispatch.domain.intervention;

import java.util.Arrays;
import java.util.Optional;

/**
 * Why an intervention was cancelled. The code travels in {@code
 * InterventionCancellationPending} and {@code InterventionCancelled}; an
 * administrator's note never does. US-09 adds the codes for Issue's events.
 */
public enum CancelReason {
    NO_LONGER_NEEDED("no_longer_needed"),
    CONTRACTOR_UNAVAILABLE("contractor_unavailable"),
    RESCHEDULING("rescheduling"),
    OTHER("other");

    private final String value;

    CancelReason(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<CancelReason> fromValue(String value) {
        return Arrays.stream(values()).filter(r -> r.value.equals(value)).findFirst();
    }
}
