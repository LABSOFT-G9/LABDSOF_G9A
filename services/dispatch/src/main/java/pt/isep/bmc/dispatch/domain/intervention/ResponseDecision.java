package pt.isep.bmc.dispatch.domain.intervention;

import java.util.Arrays;
import java.util.Optional;

/** The contractor's answer to the approved proposal version (technical design 3.2). */
public enum ResponseDecision {
    ACCEPT("accept"),
    DECLINE("decline"),
    COUNTERPROPOSE("counterpropose");

    private final String value;

    ResponseDecision(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<ResponseDecision> fromValue(String value) {
        return Arrays.stream(values()).filter(d -> d.value.equals(value)).findFirst();
    }
}
