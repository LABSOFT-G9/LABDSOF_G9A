package pt.isep.bmc.issue.domain.issue;

import java.util.Objects;
import java.util.Optional;

/**
 * The summary an administrator publishes for the whole building (US-04):
 * plain text, trimmed, 10 to 500 characters, counted as Unicode code points.
 * Control characters other than tab and line breaks, and lone surrogates, are
 * rejected.
 *
 * <p>It is the only text about an issue that residents of the building see;
 * the report text stays private.
 */
public final class SummaryText {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 500;

    private final String value;

    private SummaryText(String value) {
        this.value = value;
    }

    /**
     * Checks user input.
     *
     * @return the reason why the input is invalid ({@code required},
     *         {@code too_short}, {@code too_long} or {@code invalid_characters}), or empty if it is valid
     */
    public static Optional<String> violation(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of("required");
        }
        String trimmed = raw.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < MIN_LENGTH) {
            return Optional.of("too_short");
        }
        if (length > MAX_LENGTH) {
            return Optional.of("too_long");
        }
        if (trimmed.codePoints().anyMatch(SummaryText::isForbidden)) {
            return Optional.of("invalid_characters");
        }
        return Optional.empty();
    }

    /** Trims valid user input; check it with {@link #violation} first. */
    public static SummaryText of(String raw) {
        violation(raw).ifPresent(reason -> {
            throw new IllegalArgumentException("invalid summary: " + reason);
        });
        return new SummaryText(raw.strip());
    }

    /** Rebuilds a summary that was validated before it was stored. */
    public static SummaryText restore(String stored) {
        return new SummaryText(Objects.requireNonNull(stored, "stored"));
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
        return other instanceof SummaryText text && text.value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** Public inside one building only, so not printed either. */
    @Override
    public String toString() {
        return "SummaryText[" + value.codePointCount(0, value.length()) + " characters]";
    }
}
