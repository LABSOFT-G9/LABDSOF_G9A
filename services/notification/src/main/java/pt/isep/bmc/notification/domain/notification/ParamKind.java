package pt.isep.bmc.notification.domain.notification;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What a template parameter may hold. Parameters carry identifiers and labels,
 * never private text (technical design 4.4, ADR-007), so every kind is short
 * and has a fixed shape. There is deliberately no free-text kind.
 */
public enum ParamKind {

    /** A UUID in canonical form, for example an issue ID. */
    ID {
        @Override
        Optional<String> check(String value) {
            return UUID_FORM.matcher(value).matches() ? Optional.empty() : Optional.of("invalid_format");
        }
    },

    /** A lower-case token from a fixed set of the producer, for example {@code plumbing} or {@code accepted}. */
    TOKEN {
        @Override
        Optional<String> check(String value) {
            return TOKEN_FORM.matcher(value).matches() ? Optional.empty() : Optional.of("invalid_format");
        }
    },

    /** A short display label such as an area name: at most {@value #LABEL_MAX} code points, one line. */
    LABEL {
        @Override
        Optional<String> check(String value) {
            if (value.isBlank()) {
                return Optional.of("required");
            }
            if (value.codePointCount(0, value.length()) > LABEL_MAX) {
                return Optional.of("too_long");
            }
            return value.codePoints().anyMatch(Character::isISOControl) ? Optional.of("invalid_format") : Optional.empty();
        }
    },

    /** An RFC 3339 instant in UTC, for example a booked window's start. */
    TIME {
        @Override
        Optional<String> check(String value) {
            if (value.length() > 40) {
                return Optional.of("invalid_format");
            }
            try {
                Instant.parse(value);
                return Optional.empty();
            } catch (DateTimeParseException e) {
                return Optional.of("invalid_format");
            }
        }
    };

    public static final int LABEL_MAX = 120;

    private static final Pattern UUID_FORM =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern TOKEN_FORM = Pattern.compile("[a-z][a-z0-9_]{0,39}");

    /** @return the violation reason, or empty if the value fits */
    abstract Optional<String> check(String value);
}
