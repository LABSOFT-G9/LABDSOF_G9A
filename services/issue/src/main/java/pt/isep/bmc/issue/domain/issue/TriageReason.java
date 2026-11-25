package pt.isep.bmc.issue.domain.issue;

import java.util.Objects;
import java.util.Optional;

/**
 * Why an administrator decided a category and urgency: trimmed, 1 to 500
 * characters (US-02), counted as Unicode code points. Control characters
 * other than tab and line breaks, and lone surrogates, are rejected.
 *
 * <p>Internal: shown to the building's administrators only, because it may
 * quote a private report. {@link #toString()} never returns the text.
 */
public final class TriageReason {

    public static final int MAX_LENGTH = 500;

    private final String value;

    private TriageReason(String value) {
        this.value = value;
    }

    /**
     * Checks user input.
     *
     * @return the reason why the input is invalid ({@code required},
     *         {@code too_long} or {@code invalid_characters}), or empty if it is valid
     */
    public static Optional<String> violation(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of("required");
        }
        String trimmed = raw.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_LENGTH) {
            return Optional.of("too_long");
        }
        if (trimmed.codePoints().anyMatch(TriageReason::isForbidden)) {
            return Optional.of("invalid_characters");
        }
        return Optional.empty();
    }

    /** Trims valid user input; check it with {@link #violation} first. */
    public static TriageReason of(String raw) {
        violation(raw).ifPresent(reason -> {
            throw new IllegalArgumentException("invalid triage reason: " + reason);
        });
        return new TriageReason(raw.strip());
    }

    /** Rebuilds a reason that was validated before it was stored. */
    public static TriageReason restore(String stored) {
        return new TriageReason(Objects.requireNonNull(stored, "stored"));
    }

    private static boolean isForbidden(int codePoint) {
        return (Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')
                || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TriageReason reason && reason.value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "TriageReason[redacted]";
    }
}
