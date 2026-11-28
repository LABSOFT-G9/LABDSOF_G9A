package pt.isep.bmc.issue.adapters.in.messaging;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import pt.isep.bmc.issue.application.command.ApplyDispatchEventCommand;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.dispatch.DispatchEvent;
import pt.isep.bmc.issue.domain.dispatch.DispatchEventType;
import pt.isep.bmc.issue.domain.dispatch.InterventionState;
import pt.isep.bmc.issue.domain.dispatch.VisitWindow;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.shared.FieldViolation;
import pt.isep.bmc.issue.domain.shared.ValidationException;
import tools.jackson.databind.JsonNode;

/**
 * Reads one Dispatch event from {@code issue.inbox}: the CloudEvents envelope
 * as Dispatch's outbox writes it (technical design 4.4), with the intervention
 * as {@code subject} and its version as {@code aggregateversion}.
 *
 * <p>A message this cannot read is a {@link ValidationException} naming each
 * invalid field and why, never a value. The listener of EN-02-02 (#78) rejects
 * it to the dead-letter queue without retry: retrying cannot fix it. Fields it
 * does not know are ignored, so Dispatch can add one without breaking Issue.
 */
public final class DispatchEvents {

    /** The only {@code schemaversion} Issue reads; another major version is refused, not guessed. */
    static final int SCHEMA_VERSION = 1;

    private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private static final Set<String> DECISIONS = Set.of("accept", "counterpropose");
    private static final Set<String> BOOKING_STATES =
            Set.of("requested", "uncertain", "rejected", "failed", "discrepancy", "cancelled");
    private static final int MAX_USER_ID = 255;

    private DispatchEvents() {
    }

    public static ApplyDispatchEventCommand parse(JsonNode envelope) {
        if (envelope == null || !envelope.isObject()) {
            throw ValidationException.of("envelope", "invalid_type");
        }
        List<FieldViolation> violations = new ArrayList<>();
        Optional<Long> schema = number(envelope, "schemaversion", violations);
        if (schema.isPresent() && schema.get() != SCHEMA_VERSION) {
            violations.add(new FieldViolation("schemaversion", "unsupported_version"));
        }
        Optional<String> eventId = text(envelope, "id", violations).filter(id -> matches(id, EVENT_ID, "id",
                violations));
        Optional<DispatchEventType> type = text(envelope, "type", violations)
                .flatMap(raw -> known(DispatchEventType.fromValue(raw), "type", violations));
        Optional<UUID> interventionId = uuid(envelope, "subject", violations);
        Optional<Instant> occurredAt = instant(envelope, "time", "time", violations);
        Optional<Long> version = number(envelope, "aggregateversion", violations)
                .filter(v -> positive(v, "aggregateversion", violations));
        Optional<UUID> buildingId = uuid(envelope, "buildingid", violations);
        Optional<String> correlationId = text(envelope, "correlationid", violations);

        JsonNode data = envelope.get("data");
        if (data == null || !data.isObject()) {
            violations.add(new FieldViolation("data", data == null || data.isNull() ? "required" : "invalid_type"));
            throw new ValidationException(violations);
        }
        Optional<UUID> issueId = uuid(data, "issueId", "data.issueId", violations);
        Optional<InterventionState> state = text(data, "interventionState", "data.interventionState", violations)
                .flatMap(raw -> known(InterventionState.fromValue(raw), "data.interventionState", violations));

        Optional<String> code = Optional.empty();
        Optional<VisitWindow> window = Optional.empty();
        boolean manual = false;
        Optional<String> contractor = Optional.empty();
        if (type.isPresent()) {
            switch (type.get()) {
                case INTERVENTION_APPROVED -> contractor = userId(data, true, violations);
                case INTERVENTION_TERMS_CHANGED -> contractor = userId(data, false, violations);
                case CONTRACTOR_RESPONDED -> code = text(data, "decision", "data.decision", violations)
                        .filter(d -> oneOf(d, DECISIONS, "data.decision", violations));
                case INTERVENTION_DECLINED, INTERVENTION_CANCELLATION_PENDING, INTERVENTION_CANCELLED ->
                        code = text(data, "reasonCode", "data.reasonCode", violations)
                                .filter(c -> matches(c, CODE, "data.reasonCode", violations));
                case BOOKING_STATE_CHANGED -> code = text(data, "bookingState", "data.bookingState", violations)
                        .filter(s -> oneOf(s, BOOKING_STATES, "data.bookingState", violations));
                case BOOKING_CONFIRMED -> {
                    window = window(data, violations);
                    Optional<String> source = text(data, "source", "data.source", violations)
                            .filter(s -> oneOf(s, Set.of("calendar", "manual"), "data.source", violations));
                    manual = source.filter("manual"::equals).isPresent();
                }
                case INTERVENTION_STARTED, COMPLETION_REPORTED, REWORK_REQUESTED -> {
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        DispatchEvent event = new DispatchEvent(eventId.orElseThrow(), type.orElseThrow(),
                interventionId.orElseThrow(), version.orElseThrow(), new BuildingId(buildingId.orElseThrow()),
                new IssueId(issueId.orElseThrow()), state.orElseThrow(), occurredAt.orElseThrow(), code, window,
                manual, contractor);
        return new ApplyDispatchEventCommand(event, correlationId.orElseThrow());
    }

    private static Optional<String> userId(JsonNode data, boolean required, List<FieldViolation> violations) {
        JsonNode value = data.get("contractorUserId");
        if (!required && (value == null || value.isNull())) {
            return Optional.empty();
        }
        return text(data, "contractorUserId", "data.contractorUserId", violations).filter(user -> {
            if (user.isBlank() || user.length() > MAX_USER_ID) {
                violations.add(new FieldViolation("data.contractorUserId", "invalid_format"));
                return false;
            }
            return true;
        });
    }

    private static Optional<VisitWindow> window(JsonNode data, List<FieldViolation> violations) {
        JsonNode window = data.get("window");
        if (window == null || window.isNull()) {
            violations.add(new FieldViolation("data.window", "required"));
            return Optional.empty();
        }
        if (!window.isObject()) {
            violations.add(new FieldViolation("data.window", "invalid_type"));
            return Optional.empty();
        }
        Optional<Instant> start = instant(window, "start", "data.window.start", violations);
        Optional<Instant> end = instant(window, "end", "data.window.end", violations);
        if (start.isEmpty() || end.isEmpty()) {
            return Optional.empty();
        }
        if (!end.get().isAfter(start.get())) {
            violations.add(new FieldViolation("data.window.end", "not_after_start"));
            return Optional.empty();
        }
        return Optional.of(new VisitWindow(start.get(), end.get()));
    }

    private static Optional<String> text(JsonNode node, String field, List<FieldViolation> violations) {
        return text(node, field, field, violations);
    }

    private static Optional<String> text(JsonNode node, String field, String path, List<FieldViolation> violations) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            violations.add(new FieldViolation(path, "required"));
            return Optional.empty();
        }
        if (!value.isString()) {
            violations.add(new FieldViolation(path, "invalid_type"));
            return Optional.empty();
        }
        return Optional.of(value.asString());
    }

    private static Optional<Long> number(JsonNode node, String field, List<FieldViolation> violations) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            violations.add(new FieldViolation(field, "required"));
            return Optional.empty();
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            violations.add(new FieldViolation(field, "invalid_type"));
            return Optional.empty();
        }
        return Optional.of(value.longValue());
    }

    private static Optional<UUID> uuid(JsonNode node, String field, List<FieldViolation> violations) {
        return uuid(node, field, field, violations);
    }

    private static Optional<UUID> uuid(JsonNode node, String field, String path, List<FieldViolation> violations) {
        return text(node, field, path, violations).flatMap(raw -> {
            try {
                UUID parsed = UUID.fromString(raw);
                // UUID.fromString accepts short groups ("1-2-3-4-5"); only the canonical form is an ID.
                if (parsed.toString().equals(raw.toLowerCase())) {
                    return Optional.of(parsed);
                }
            } catch (IllegalArgumentException invalid) {
                // reported below
            }
            violations.add(new FieldViolation(path, "invalid_format"));
            return Optional.empty();
        });
    }

    private static Optional<Instant> instant(JsonNode node, String field, String path,
                                             List<FieldViolation> violations) {
        return text(node, field, path, violations).flatMap(raw -> {
            try {
                return Optional.of(Instant.parse(raw));
            } catch (DateTimeParseException invalid) {
                violations.add(new FieldViolation(path, "invalid_format"));
                return Optional.empty();
            }
        });
    }

    private static <T> Optional<T> known(Optional<T> value, String path, List<FieldViolation> violations) {
        if (value.isEmpty()) {
            violations.add(new FieldViolation(path, "unknown_value"));
        }
        return value;
    }

    private static boolean matches(String value, Pattern pattern, String path, List<FieldViolation> violations) {
        if (pattern.matcher(value).matches()) {
            return true;
        }
        violations.add(new FieldViolation(path, "invalid_format"));
        return false;
    }

    private static boolean oneOf(String value, Set<String> allowed, String path, List<FieldViolation> violations) {
        if (allowed.contains(value)) {
            return true;
        }
        violations.add(new FieldViolation(path, "unknown_value"));
        return false;
    }

    private static boolean positive(long value, String path, List<FieldViolation> violations) {
        if (value >= 1) {
            return true;
        }
        violations.add(new FieldViolation(path, "out_of_range"));
        return false;
    }
}
