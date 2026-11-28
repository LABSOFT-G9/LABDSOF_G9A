package pt.isep.bmc.notification.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.notification.application.port.out.Inbox;
import pt.isep.bmc.notification.domain.notification.Notification;
import pt.isep.bmc.notification.domain.notification.Priority;
import pt.isep.bmc.notification.domain.shared.UserId;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code notifications} table that the in-app channel writes. IDs are
 * UUIDv7, so ordering by ID is ordering by creation time. Every statement
 * filters by {@code recipient_user_id}.
 */
@Repository
class JdbcInbox implements Inbox {

    private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcInbox(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<Notification> findForRecipient(UserId recipient, Optional<UUID> before, int limit) {
        String sql = "SELECT id, request_id, recipient_user_id, template_key, params, priority, source_event_id,"
                + " created_at, read_at FROM notifications WHERE recipient_user_id = :recipient"
                + (before.isPresent() ? " AND id < :before" : "")
                + " ORDER BY id DESC LIMIT :limit";
        var query = jdbc.sql(sql)
                .param("recipient", recipient.value())
                .param("limit", limit);
        if (before.isPresent()) {
            query = query.param("before", before.get().toString());
        }
        return query.query(this::row).list();
    }

    @Override
    public long countUnread(UserId recipient) {
        return jdbc.sql("SELECT COUNT(*) FROM notifications WHERE recipient_user_id = :recipient AND read_at IS NULL")
                .param("recipient", recipient.value())
                .query(Long.class)
                .single();
    }

    @Override
    public boolean markRead(UserId recipient, UUID notificationId, Instant now) {
        int changed = jdbc.sql("""
                        UPDATE notifications SET read_at = :now
                         WHERE id = :id AND recipient_user_id = :recipient AND read_at IS NULL
                        """)
                .param("now", Timestamps.toColumn(now))
                .param("id", notificationId.toString())
                .param("recipient", recipient.value())
                .update();
        if (changed == 1) {
            return true;
        }
        // Already read, or not this recipient's: only the first is a success.
        return jdbc.sql("SELECT COUNT(*) FROM notifications WHERE id = :id AND recipient_user_id = :recipient")
                .param("id", notificationId.toString())
                .param("recipient", recipient.value())
                .query(Long.class)
                .single() == 1;
    }

    @Override
    public int markAllRead(UserId recipient, Instant now) {
        return jdbc.sql("UPDATE notifications SET read_at = :now WHERE recipient_user_id = :recipient AND read_at IS NULL")
                .param("now", Timestamps.toColumn(now))
                .param("recipient", recipient.value())
                .update();
    }

    private Notification row(ResultSet rs, int n) throws SQLException {
        return new Notification(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("request_id")),
                new UserId(rs.getString("recipient_user_id")),
                rs.getString("template_key"),
                json.readValue(rs.getString("params"), PARAMS),
                Priority.fromToken(rs.getString("priority")).orElse(Priority.NORMAL),
                rs.getString("source_event_id"),
                Timestamps.fromColumn(rs.getObject("created_at", LocalDateTime.class)),
                Timestamps.fromColumn(rs.getObject("read_at", LocalDateTime.class)));
    }
}
