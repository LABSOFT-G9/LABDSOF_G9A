package pt.isep.bmc.dispatch.domain.shared;

import java.util.List;

/** Input does not satisfy the rules ({@code 400 VALIDATION_FAILED}). */
public final class ValidationException extends DomainException {

    private final List<FieldViolation> violations;

    public ValidationException(List<FieldViolation> violations) {
        super("VALIDATION_FAILED", "The request has invalid fields.");
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("at least one violation is required");
        }
        this.violations = List.copyOf(violations);
    }

    public static ValidationException of(String field, String reason) {
        return new ValidationException(List.of(new FieldViolation(field, reason)));
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
