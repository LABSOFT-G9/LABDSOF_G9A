package pt.isep.bmc.messaging.outbox;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads and updates the service's {@code outbox} table (see the README for the
 * columns the table needs). The service writes the rows itself, in the same
 * transaction as its change; this class only publishes them.
 */
public class OutboxStore {

    private final JdbcClient jdbc;

    public OutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The oldest pending rows, locked until the caller's transaction ends.
     * Rows locked by another publisher are skipped, so two instances never
     * send the same row at the same time.
     */
    public List<OutboxRow> lockPending(int limit, Set<String> notPublishedTypes) {
        return jdbc.sql("SELECT id, routing_key, payload FROM outbox WHERE status = 'pending'"
                        + typeFilter(notPublishedTypes)
                        + " ORDER BY created_at, id LIMIT :limit FOR UPDATE SKIP LOCKED")
                .params(typeParams(notPublishedTypes))
                .param("limit", limit)
                .query((rs, rowNum) ->
                        new OutboxRow(rs.getString("id"), rs.getString("routing_key"), rs.getString("payload")))
                .list();
    }

    public void markSent(String id, Instant now) {
        jdbc.sql("UPDATE outbox SET status = 'sent', attempts = attempts + 1, published_at = :now WHERE id = :id")
                .param("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("id", id)
                .update();
    }

    public void recordFailedAttempt(String id) {
        jdbc.sql("UPDATE outbox SET attempts = attempts + 1 WHERE id = :id").param("id", id).update();
    }

    /** How many rows wait to be published, and for how long the oldest has waited. */
    public Backlog backlog(Set<String> notPublishedTypes, Instant now) {
        return jdbc.sql("SELECT COUNT(*) AS pending, MIN(created_at) AS oldest FROM outbox WHERE status = 'pending'"
                        + typeFilter(notPublishedTypes))
                .params(typeParams(notPublishedTypes))
                .query((rs, rowNum) -> {
                    LocalDateTime oldest = rs.getObject("oldest", LocalDateTime.class);
                    Duration age = oldest == null
                            ? Duration.ZERO
                            : Duration.between(oldest.toInstant(ZoneOffset.UTC), now);
                    return new Backlog(rs.getLong("pending"), age.isNegative() ? Duration.ZERO : age);
                })
                .single();
    }

    private static String typeFilter(Set<String> notPublishedTypes) {
        return notPublishedTypes.isEmpty() ? "" : " AND event_type NOT IN (:notPublished)";
    }

    private static java.util.Map<String, ?> typeParams(Set<String> notPublishedTypes) {
        return notPublishedTypes.isEmpty() ? java.util.Map.of() : java.util.Map.of("notPublished", notPublishedTypes);
    }

    /** Rows waiting to be published and the age of the oldest one. */
    public record Backlog(long pending, Duration oldestAge) {
    }
}
