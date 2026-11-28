package pt.isep.bmc.notification.domain.notification;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import pt.isep.bmc.notification.domain.shared.UserId;

/**
 * One recipient's notification of a request: what every channel delivers. At
 * most one is delivered per {@code requestId}, recipient and channel, however
 * often the request arrives. It holds the template key and its checked
 * parameters, never rendered or private text.
 *
 * @param id            the notification's own ID (UUIDv7); the in-app inbox shows it to the client
 * @param requestId     the {@code notificationId} of the request
 * @param sourceEventId the event that caused the request, for tracing
 * @param readAt        when the recipient marked it read in the inbox; {@code null} while unread
 */
public record Notification(
        UUID id,
        UUID requestId,
        UserId recipient,
        String templateKey,
        Map<String, String> params,
        Priority priority,
        String sourceEventId,
        Instant createdAt,
        Instant readAt) {

    public Notification {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(templateKey, "templateKey");
        params = Map.copyOf(params);
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** The notification for one recipient of a request. */
    public static Notification forRecipient(UUID id, NotificationRequest request, UserId recipient, Instant now) {
        return new Notification(id, request.notificationId(), recipient, request.template().key(),
                request.params(), request.priority(), request.sourceEventId(), now, null);
    }

    public boolean isRead() {
        return readAt != null;
    }

    public Optional<Instant> readAtTime() {
        return Optional.ofNullable(readAt);
    }

    /** The sentence shown to the recipient. */
    public String title() {
        return TemplateCatalogue.find(templateKey).map(Template::title).orElse(TemplateCatalogue.FALLBACK_TITLE);
    }

    /** The client path to open, if the template has one. */
    public Optional<String> link() {
        return TemplateCatalogue.find(templateKey).flatMap(template -> template.link(params));
    }
}
