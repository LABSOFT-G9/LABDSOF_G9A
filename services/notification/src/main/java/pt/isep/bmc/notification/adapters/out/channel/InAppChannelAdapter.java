package pt.isep.bmc.notification.adapters.out.channel;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import pt.isep.bmc.notification.adapters.out.persistence.Timestamps;
import pt.isep.bmc.notification.application.port.out.DeliveryResult;
import pt.isep.bmc.notification.application.port.out.NotificationChannel;
import pt.isep.bmc.notification.domain.notification.ChannelId;
import pt.isep.bmc.notification.domain.notification.Notification;
import tools.jackson.databind.json.JsonMapper;

/**
 * The in-app channel (ADR-007): writes the notification into the recipient's
 * inbox, read back through {@code GET /notifications}. Always on, and done
 * inside the receiving transaction, so it is {@link DeliveryResult#SENT} at
 * once. The inbox's unique key (request, recipient) backs up the delivery
 * log's.
 */
@Component
class InAppChannelAdapter implements NotificationChannel {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    InAppChannelAdapter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public ChannelId id() {
        return ChannelId.IN_APP;
    }

    @Override
    public DeliveryResult deliver(Notification notification) {
        jdbc.sql("""
                        INSERT INTO notifications
                          (id, request_id, recipient_user_id, template_key, params, priority, source_event_id,
                           created_at)
                        VALUES (:id, :request, :recipient, :template, :params, :priority, :source, :created)
                        """)
                .param("id", notification.id().toString())
                .param("request", notification.requestId().toString())
                .param("recipient", notification.recipient().value())
                .param("template", notification.templateKey())
                .param("params", json.writeValueAsString(notification.params()))
                .param("priority", notification.priority().token())
                .param("source", notification.sourceEventId())
                .param("created", Timestamps.toColumn(notification.createdAt()))
                .update();
        return DeliveryResult.SENT;
    }
}
