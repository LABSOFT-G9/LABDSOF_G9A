package pt.isep.bmc.issue.application.idempotency;

import pt.isep.bmc.issue.domain.shared.ValidationException;

/**
 * Value of the {@code Idempotency-Key} header (technical design 4.1): 1 to 128
 * printable ASCII characters without spaces. A UUID is the expected form.
 */
public record IdempotencyKey(String value) {

    public static final int MAX_LENGTH = 128;
    public static final String FIELD = "Idempotency-Key";

    public IdempotencyKey {
        if (value == null || value.isEmpty()) {
            throw ValidationException.of(FIELD, "required");
        }
        if (value.length() > MAX_LENGTH || !value.chars().allMatch(c -> c >= 0x21 && c <= 0x7E)) {
            throw ValidationException.of(FIELD, "invalid_format");
        }
    }
}
