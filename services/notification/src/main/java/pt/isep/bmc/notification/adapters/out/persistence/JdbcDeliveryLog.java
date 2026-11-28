package pt.isep.bmc.notification.adapters.out.persistence;

import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.notification.application.port.out.DeliveryLog;
import pt.isep.bmc.notification.application.port.out.DeliveryResult;
import pt.isep.bmc.notification.domain.notification.ChannelId;
import pt.isep.bmc.notification.domain.notification.Notification;

/**
 * The {@code deliveries} table. Its primary key (request, recipient, channel)
 * is the idempotency gate: a second transaction inserting the same key waits
 * for the first one and then fails with a duplicate key, which in MySQL undoes
 * only that statement, so its own transaction goes on.
 */
@Repository
class JdbcDeliveryLog implements DeliveryLog {

    private final JdbcClient jdbc;

    JdbcDeliveryLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean claim(Notification notification, ChannelId channel, Instant now) {
        try {
            jdbc.sql("""
                            INSERT INTO deliveries
                              (request_id, recipient_user_id, channel, state, attempts, notification_id,
                               created_at, updated_at)
                            VALUES (:request, :recipient, :channel, 'pending', 0, :id, :now, :now)
                            """)
                    .param("request", notification.requestId().toString())
                    .param("recipient", notification.recipient().value())
                    .param("channel", channel.value())
                    .param("id", notification.id().toString())
                    .param("now", Timestamps.toColumn(now))
                    .update();
            return true;
        } catch (DuplicateKeyException alreadyClaimed) {
            return false;
        }
    }

    @Override
    public void record(Notification notification, ChannelId channel, DeliveryResult result, Instant now) {
        jdbc.sql("""
                        UPDATE deliveries
                           SET state = :state, attempts = attempts + 1, updated_at = :now
                         WHERE request_id = :request AND recipient_user_id = :recipient AND channel = :channel
                        """)
                .param("state", result.state())
                .param("now", Timestamps.toColumn(now))
                .param("request", notification.requestId().toString())
                .param("recipient", notification.recipient().value())
                .param("channel", channel.value())
                .update();
    }
}
