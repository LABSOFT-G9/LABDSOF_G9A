package pt.isep.bmc.issue.domain.report;

import java.util.Objects;
import pt.isep.bmc.issue.domain.shared.ValidationException;

/**
 * The private text of a report: trimmed, 10 to 4,000 characters (US-01),
 * counted as Unicode code points so that an emoji counts as one character.
 * Control characters other than tab and line breaks are rejected.
 *
 * <p>{@link #toString()} never returns the text, so it cannot leak into logs
 * by accident (US-01: private text stays out of logs).
 */
public final class ReportText {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 4_000;
    static final String FIELD = "text";

    private final String value;

    private ReportText(String value) {
        this.value = value;
    }

    /** Validates and trims user input. */
    public static ReportText of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ValidationException.of(FIELD, "required");
        }
        String trimmed = raw.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < MIN_LENGTH) {
            throw ValidationException.of(FIELD, "too_short");
        }
        if (length > MAX_LENGTH) {
            throw ValidationException.of(FIELD, "too_long");
        }
        if (trimmed.codePoints().anyMatch(ReportText::isForbidden)) {
            throw ValidationException.of(FIELD, "invalid_characters");
        }
        return new ReportText(trimmed);
    }

    /** Rebuilds text that was validated before it was stored. */
    public static ReportText restore(String stored) {
        return new ReportText(Objects.requireNonNull(stored, "stored"));
    }

    private static boolean isForbidden(int codePoint) {
        return Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\r' && codePoint != '\t';
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReportText text && text.value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "ReportText[redacted]";
    }
}
