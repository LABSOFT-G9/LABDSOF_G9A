package pt.isep.bmc.issue.domain.issue;

import java.util.Arrays;
import java.util.Optional;

/** Urgency levels an administrator can decide (US-02), lowest first. */
public enum IssueUrgency {
    LOW("low"),
    NORMAL("normal"),
    HIGH("high"),
    CRITICAL("critical");

    private final String value;

    IssueUrgency(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<IssueUrgency> fromValue(String value) {
        return Arrays.stream(values()).filter(urgency -> urgency.value.equals(value)).findFirst();
    }
}
