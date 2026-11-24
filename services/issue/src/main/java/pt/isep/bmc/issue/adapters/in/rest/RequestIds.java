package pt.isep.bmc.issue.adapters.in.rest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import pt.isep.bmc.issue.domain.shared.FieldViolation;

/** Parses UUIDs (strict canonical form only) and paging parameters from requests. */
final class RequestIds {

    private static final Pattern CANONICAL =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private RequestIds() {
    }

    static Optional<UUID> parse(String raw) {
        if (raw == null || !CANONICAL.matcher(raw).matches()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(raw));
    }

    static Optional<FieldViolation> check(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(new FieldViolation(field, "required"));
        }
        return parse(raw).isPresent() ? Optional.empty() : Optional.of(new FieldViolation(field, "invalid_format"));
    }

    /**
     * An optional integer query parameter within {@code [min, max]}. Outside
     * the range or not a number adds an {@code out_of_range} violation.
     */
    static int intParam(String raw, int fallback, int min, int max, String field, List<FieldViolation> out) {
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        out.add(new FieldViolation(field, "out_of_range"));
        return fallback;
    }
}
