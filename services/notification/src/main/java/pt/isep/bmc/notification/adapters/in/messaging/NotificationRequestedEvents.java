package pt.isep.bmc.notification.adapters.in.messaging;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pt.isep.bmc.notification.domain.notification.NotificationRequest;
import pt.isep.bmc.notification.domain.shared.FieldViolation;
import pt.isep.bmc.notification.domain.shared.ValidationException;
import tools.jackson.databind.JsonNode;

/**
 * Reads the {@code data} of {@code NotificationRequested}: the proto3 JSON
 * mapping of {@code contracts/proto/bmc/notification/v1/notification_requested.proto}
 * (camelCase names, absent optional fields left out). Unknown fields are
 * ignored, as the contracts README asks of consumers.
 *
 * <p>Invitations ({@code recipientAddress} and {@code inviteLink}) need the
 * email channel, which US-10-01 does not build: they are refused with
 * {@code unsupported_channel} rather than stored as an inbox row nobody owns.
 */
public final class NotificationRequestedEvents {

    public static final String NOTIFICATION_REQUESTED = "NotificationRequested";

    private NotificationRequestedEvents() {
    }

    /** @throws ValidationException with every invalid field; never quotes a value */
    public static NotificationRequest parse(JsonNode data) {
        if (data == null || !data.isObject()) {
            throw ValidationException.of("data", "invalid_type");
        }
        List<FieldViolation> violations = new ArrayList<>();
        if (present(data, "recipientAddress") || present(data, "inviteLink")) {
            violations.add(new FieldViolation("recipientAddress", "unsupported_channel"));
        }
        String notificationId = text(data, "notificationId", violations);
        String sourceEventId = text(data, "sourceEventId", violations);
        List<String> recipients = texts(data, "recipientUserIds", violations);
        String templateKey = text(data, "templateKey", violations);
        Map<String, String> params = params(data, violations);
        String priority = text(data, "priority", violations);

        List<FieldViolation> all = new ArrayList<>(violations);
        try {
            NotificationRequest request =
                    NotificationRequest.of(notificationId, sourceEventId, recipients, templateKey, params, priority);
            if (all.isEmpty()) {
                return request;
            }
        } catch (ValidationException invalid) {
            for (FieldViolation violation : invalid.violations()) {
                if (all.stream().noneMatch(v -> v.field().equals(violation.field()))) {
                    all.add(violation);
                }
            }
        }
        throw new ValidationException(all);
    }

    private static boolean present(JsonNode data, String field) {
        JsonNode value = data.get(field);
        return value != null && !value.isNull() && !(value.isString() && value.asString().isEmpty());
    }

    /** An absent or {@code null} field is read as absent; any other non-string is a type error. */
    private static String text(JsonNode data, String field, List<FieldViolation> violations) {
        JsonNode value = data.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            violations.add(new FieldViolation(field, "invalid_type"));
            return null;
        }
        return value.asString();
    }

    private static List<String> texts(JsonNode data, String field, List<FieldViolation> violations) {
        JsonNode value = data.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            violations.add(new FieldViolation(field, "invalid_type"));
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isString()) {
                violations.add(new FieldViolation(field, "invalid_type"));
                return List.of();
            }
            items.add(item.asString());
        }
        return items;
    }

    private static Map<String, String> params(JsonNode data, List<FieldViolation> violations) {
        JsonNode value = data.get("params");
        if (value == null || value.isNull()) {
            return Map.of();
        }
        if (!value.isObject()) {
            violations.add(new FieldViolation("params", "invalid_type"));
            return Map.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            if (!entry.getValue().isString()) {
                // proto3 map<string, string>: every value is a JSON string.
                violations.add(new FieldViolation("params", "invalid_type"));
                return Map.of();
            }
            params.put(entry.getKey(), entry.getValue().asString());
        }
        return params;
    }
}
