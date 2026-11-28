package pt.isep.bmc.notification.adapters.in.rest;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import pt.isep.bmc.notification.domain.shared.FieldViolation;

/** Parses UUIDs from requests: strict canonical form only. */
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
}
