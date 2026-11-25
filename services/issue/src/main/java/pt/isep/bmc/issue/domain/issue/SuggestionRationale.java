package pt.isep.bmc.issue.domain.issue;

import java.util.Objects;
import java.util.Optional;

/**
 * The short explanation that comes with a triage suggestion: trimmed, 1 to
 * 240 characters (US-03), counted as Unicode code points. Control characters
 * other than tab and line breaks, and lone surrogates, are rejected.
 *
 * <p>Internal: it is written from a private report, so only the building's
 * administrators see it, and never in a list, a history entry or an event.
 * {@link #toString()} never returns the text.
 */
public final class SuggestionRationale {

    public static final int MAX_LENGTH = 240;

    private final String value;

    private SuggestionRationale(String value) {
        this.value = value;
    }

    /**
     * Checks a received rationale.
     *
     * @return the reason why it is invalid ({@code required}, {@code too_long}
     *         or {@code invalid_characters}), or empty if it is valid
     */
    public static Optional<String> violation(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of("required");
        }
        String trimmed = raw.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_LENGTH) {
            return Optional.of("too_long");
        }
        if (trimmed.codePoints().anyMatch(SuggestionRationale::isForbidden)) {
            return Optional.of("invalid_characters");
        }
        return Optional.empty();
    }

    /** Trims a valid rationale; check it with {@link #violation} first. */
    public static SuggestionRationale of(String raw) {
        violation(raw).ifPresent(reason -> {
            throw new IllegalArgumentException("invalid suggestion rationale: " + reason);
        });
        return new SuggestionRationale(raw.strip());
    }

    /** Rebuilds a rationale that was validated before it was stored. */
    public static SuggestionRationale restore(String stored) {
        return new SuggestionRationale(Objects.requireNonNull(stored, "stored"));
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
        return other instanceof SuggestionRationale rationale && rationale.value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "SuggestionRationale[redacted]";
    }
}
