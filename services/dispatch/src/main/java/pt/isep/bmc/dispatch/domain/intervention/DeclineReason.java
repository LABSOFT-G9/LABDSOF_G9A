package pt.isep.bmc.dispatch.domain.intervention;

import java.util.Arrays;
import java.util.Optional;

/** Why a contractor declined (US-07). The code travels in {@code InterventionDeclined}; free text never does. */
public enum DeclineReason {
    UNAVAILABLE("unavailable"),
    OUTSIDE_EXPERTISE("outside_expertise"),
    TERMS_NOT_ACCEPTABLE("terms_not_acceptable"),
    OTHER("other");

    private final String value;

    DeclineReason(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<DeclineReason> fromValue(String value) {
        return Arrays.stream(values()).filter(r -> r.value.equals(value)).findFirst();
    }
}
