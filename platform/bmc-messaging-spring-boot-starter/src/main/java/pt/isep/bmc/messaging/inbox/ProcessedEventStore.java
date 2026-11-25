package pt.isep.bmc.messaging.inbox;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import pt.isep.bmc.messaging.CloudEvent;

/**
 * The service's {@code processed_events} table (see the README for its
 * columns): the IDs of the events already applied.
 */
public class ProcessedEventStore {

    private final JdbcClient jdbc;

    public ProcessedEventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event in the caller's transaction.
     *
     * @return false if the event was recorded before, so its effect must not be repeated
     */
    public boolean markProcessed(CloudEvent event, Instant now) {
        try {
            jdbc.sql("""
                            INSERT INTO processed_events (event_id, event_type, source, processed_at)
                            VALUES (:id, :type, :source, :now)
                            """)
                    .param("id", event.id())
                    .param("type", event.type())
                    .param("source", event.source())
                    .param("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** Deletes up to {@code limit} IDs recorded before {@code cutoff}; returns how many. */
    public int purgeBefore(Instant cutoff, int limit) {
        return jdbc.sql("DELETE FROM processed_events WHERE processed_at < :cutoff LIMIT :limit")
                .param("cutoff", LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .param("limit", limit)
                .update();
    }
}
