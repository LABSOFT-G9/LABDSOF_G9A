package pt.isep.bmc.issue.adapters.in.rest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import pt.isep.bmc.issue.domain.shared.ValidationException;

/**
 * The version a state-changing command was based on (technical design 3.3):
 * the {@code If-Match} header holds the {@code ETag} the client last received,
 * a quoted positive integer such as {@code "3"}.
 */
final class EntityVersions {

    static final String FIELD = "If-Match";

    /** Strong tags only: no {@code W/}, no list, no {@code *}. At most 18 digits, so it fits a long. */
    private static final Pattern STRONG_TAG = Pattern.compile("\"([1-9][0-9]{0,17})\"");

    private EntityVersions() {
    }

    /** @throws ValidationException when the header is missing or not a quoted version */
    static long parseIfMatch(String header) {
        if (header == null || header.isBlank()) {
            throw ValidationException.of(FIELD, "required");
        }
        Matcher tag = STRONG_TAG.matcher(header.strip());
        if (!tag.matches()) {
            throw ValidationException.of(FIELD, "invalid_format");
        }
        return Long.parseLong(tag.group(1));
    }
}
