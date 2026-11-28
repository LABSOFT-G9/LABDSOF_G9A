package pt.isep.bmc.dispatch.domain.shared;

import java.util.Optional;

/**
 * Rules for plain text typed by a person, as in Issue's report and summary
 * texts: trimmed, counted as Unicode code points, and without control
 * characters other than tab and line breaks, or lone surrogates.
 */
public final class PlainText {

    private PlainText() {
    }

    /**
     * @return {@code required}, {@code too_short}, {@code too_long} or
     *         {@code invalid_characters}, or empty if the text is valid
     */
    public static Optional<String> violation(String raw, int minLength, int maxLength) {
        if (raw == null || raw.isBlank()) {
            return Optional.of("required");
        }
        String trimmed = raw.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < minLength) {
            return Optional.of("too_short");
        }
        if (length > maxLength) {
            return Optional.of("too_long");
        }
        if (trimmed.codePoints().anyMatch(PlainText::isForbidden)) {
            return Optional.of("invalid_characters");
        }
        return Optional.empty();
    }

    private static boolean isForbidden(int codePoint) {
        return (Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')
                || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE);
    }
}
