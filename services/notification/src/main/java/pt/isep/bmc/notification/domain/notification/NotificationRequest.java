package pt.isep.bmc.notification.domain.notification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import pt.isep.bmc.notification.domain.shared.FieldViolation;
import pt.isep.bmc.notification.domain.shared.UserId;
import pt.isep.bmc.notification.domain.shared.ValidationException;

/**
 * A valid {@code NotificationRequested}: one notification, identified by
 * {@code notificationId}, for each of the producer's recipients. Built only
 * through {@link #of}, which checks every field and reports all violations at
 * once. A violation names the field and a reason, never the value.
 */
public record NotificationRequest(
        UUID notificationId,
        String sourceEventId,
        Set<UserId> recipients,
        Template template,
        Map<String, String> params,
        Priority priority) {

    /** More recipients than this are refused; Issue's largest building fan-out is well below it. */
    public static final int MAX_RECIPIENTS = 500;

    private static final Pattern UUID_FORM =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** CloudEvents IDs are UUIDs in BMC; this also keeps them safe to log. */
    private static final Pattern EVENT_ID_FORM = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    /** A parameter name that is safe to echo in a violation. */
    private static final Pattern PARAM_NAME_FORM = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    public NotificationRequest {
        Objects.requireNonNull(notificationId, "notificationId");
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        recipients = Set.copyOf(new LinkedHashSet<>(recipients));
        Objects.requireNonNull(template, "template");
        params = Map.copyOf(params);
        Objects.requireNonNull(priority, "priority");
    }

    /**
     * Validates the request's fields as they arrived.
     *
     * @param recipientUserIds the producer's recipients; repeats count once
     * @param params           {@code null} is read as no parameters
     * @param priority         {@code null} or empty is {@code normal}
     * @throws ValidationException with every invalid field
     */
    public static NotificationRequest of(
            String notificationId,
            String sourceEventId,
            List<String> recipientUserIds,
            String templateKey,
            Map<String, String> params,
            String priority) {
        List<FieldViolation> violations = new ArrayList<>();

        if (notificationId == null || notificationId.isEmpty()) {
            violations.add(new FieldViolation("notificationId", "required"));
        } else if (!UUID_FORM.matcher(notificationId).matches()) {
            violations.add(new FieldViolation("notificationId", "invalid_format"));
        }

        if (sourceEventId == null || sourceEventId.isEmpty()) {
            violations.add(new FieldViolation("sourceEventId", "required"));
        } else if (!EVENT_ID_FORM.matcher(sourceEventId).matches()) {
            violations.add(new FieldViolation("sourceEventId", "invalid_format"));
        }

        Set<UserId> recipients = recipients(recipientUserIds, violations);

        Optional<Template> template = Optional.empty();
        if (templateKey == null || templateKey.isEmpty()) {
            violations.add(new FieldViolation("templateKey", "required"));
        } else {
            template = TemplateCatalogue.find(templateKey);
            if (template.isEmpty()) {
                violations.add(new FieldViolation("templateKey", "unknown_template"));
            }
        }

        Map<String, String> checkedParams = params == null ? Map.of() : params;
        template.ifPresent(t -> checkParams(t, checkedParams, violations));

        Optional<Priority> parsedPriority = Priority.fromToken(priority);
        if (parsedPriority.isEmpty()) {
            violations.add(new FieldViolation("priority", "invalid_value"));
        }

        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        return new NotificationRequest(UUID.fromString(notificationId), sourceEventId, recipients, template.get(),
                new LinkedHashMap<>(checkedParams), parsedPriority.get());
    }

    private static Set<UserId> recipients(List<String> recipientUserIds, List<FieldViolation> violations) {
        Set<UserId> recipients = new LinkedHashSet<>();
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            violations.add(new FieldViolation("recipientUserIds", "required"));
            return recipients;
        }
        boolean invalid = false;
        for (String raw : recipientUserIds) {
            if (raw == null || raw.isBlank() || raw.length() > UserId.MAX_LENGTH
                    || raw.codePoints().anyMatch(Character::isISOControl)) {
                invalid = true;
            } else {
                recipients.add(new UserId(raw));
            }
        }
        if (invalid) {
            violations.add(new FieldViolation("recipientUserIds", "invalid_format"));
        } else if (recipients.size() > MAX_RECIPIENTS) {
            violations.add(new FieldViolation("recipientUserIds", "too_many"));
        }
        return recipients;
    }

    private static void checkParams(Template template, Map<String, String> params, List<FieldViolation> violations) {
        for (String required : template.required().keySet().stream().sorted().toList()) {
            if (!params.containsKey(required)) {
                violations.add(new FieldViolation("params." + required, "required"));
            }
        }
        for (Map.Entry<String, String> param : params.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            String name = param.getKey();
            if (name == null || !PARAM_NAME_FORM.matcher(name).matches()) {
                // The name itself could be text the producer should not have sent: never echo it.
                violations.add(new FieldViolation("params", "invalid_name"));
                continue;
            }
            Optional<ParamKind> kind = template.kindOf(name);
            if (kind.isEmpty()) {
                violations.add(new FieldViolation("params." + name, "not_allowed"));
            } else if (param.getValue() == null) {
                violations.add(new FieldViolation("params." + name, "required"));
            } else {
                kind.get().check(param.getValue())
                        .ifPresent(reason -> violations.add(new FieldViolation("params." + name, reason)));
            }
        }
    }
}
