package pt.isep.bmc.notification.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import pt.isep.bmc.notification.adapters.in.messaging.EventEnvelope;
import pt.isep.bmc.notification.adapters.in.messaging.NotificationRequestedHandler;
import pt.isep.bmc.notification.adapters.in.messaging.NotificationRequestedHandler.Outcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code NotificationRequested} as Issue or Dispatch would publish it
 * ({@code data} in the proto3 JSON mapping), handed to the same handler the
 * RabbitMQ listener will call (EN-02-02). These stand in for the real
 * producer until US-10-03 publishes.
 */
public final class SyntheticRequests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NotificationRequestedHandler handler;

    public SyntheticRequests(NotificationRequestedHandler handler) {
        this.handler = handler;
    }

    /** A fresh request: new notification and event IDs. */
    public Request request(String templateKey, Map<String, String> params, String... recipients) {
        return new Request(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "c-" + UUID.randomUUID(),
                List.of(recipients), templateKey, params, "normal");
    }

    public Outcome deliver(Request request) {
        return handler.handle(new EventEnvelope(request.eventId(), request.correlationId()), request.data());
    }

    /** One {@code NotificationRequested}; delivering it again is a redelivery of the same message. */
    public record Request(
            String notificationId,
            String eventId,
            String correlationId,
            List<String> recipients,
            String templateKey,
            Map<String, String> params,
            String priority) {

        public JsonNode data() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("notificationId", notificationId);
            data.put("sourceEventId", eventId);
            data.put("recipientUserIds", recipients);
            data.put("templateKey", templateKey);
            data.put("params", params);
            data.put("priority", priority);
            return JSON.valueToTree(data);
        }

        public Request withRecipients(String... others) {
            return new Request(notificationId, eventId, correlationId, List.of(others), templateKey, params, priority);
        }
    }
}
