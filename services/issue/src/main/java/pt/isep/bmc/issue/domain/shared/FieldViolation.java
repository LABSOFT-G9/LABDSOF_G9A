package pt.isep.bmc.issue.domain.shared;

import java.util.Objects;

/**
 * One invalid field: its name and a machine-readable reason such as
 * {@code required}, {@code too_short} or {@code invalid_format}. Never holds
 * the rejected value.
 */
public record FieldViolation(String field, String reason) {

    public FieldViolation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(reason, "reason");
    }
}
