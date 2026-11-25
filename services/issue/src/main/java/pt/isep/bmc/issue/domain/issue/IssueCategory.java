package pt.isep.bmc.issue.domain.issue;

import java.util.Arrays;
import java.util.Optional;

/** Fault categories an administrator can decide (US-02). */
public enum IssueCategory {
    PLUMBING("plumbing"),
    ELECTRICAL("electrical"),
    LIFT("lift"),
    CLEANING("cleaning"),
    STRUCTURAL("structural"),
    OTHER("other");

    private final String value;

    IssueCategory(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<IssueCategory> fromValue(String value) {
        return Arrays.stream(values()).filter(category -> category.value.equals(value)).findFirst();
    }
}
