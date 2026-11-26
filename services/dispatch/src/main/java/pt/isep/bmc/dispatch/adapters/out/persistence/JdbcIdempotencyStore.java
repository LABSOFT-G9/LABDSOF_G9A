package pt.isep.bmc.dispatch.adapters.out.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.dispatch.application.port.out.DuplicateIdempotencyKeyException;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyRecord;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyScope;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyStore;

/**
 * Plain SQL on purpose: the INSERT runs immediately (not at a JPA flush), so
 * the primary key (actor, operation, key) serialises concurrent retries at the
 * first statement of the transaction. Runs on the transaction's connection.
 */
@Repository
class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcClient jdbc;

    JdbcIdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IdempotencyRecord> find(IdempotencyScope scope) {
        List<IdempotencyRecord> rows = jdbc.sql("""
                        SELECT request_hash, response_status, resource_id, resource_version, created_at, expires_at
                          FROM idempotency_keys
                         WHERE actor_id = :actor AND operation = :operation AND idem_key = :key
                        """)
                .param("actor", scope.actor().value())
                .param("operation", scope.operation())
                .param("key", scope.key().value())
                .query((rs, n) -> new IdempotencyRecord(
                        scope,
                        rs.getString("request_hash"),
                        rs.getInt("response_status"),
                        UUID.fromString(rs.getString("resource_id")),
                        rs.getLong("resource_version"),
                        Timestamps.fromColumn(rs.getObject("created_at", LocalDateTime.class)),
                        Timestamps.fromColumn(rs.getObject("expires_at", LocalDateTime.class))))
                .list();
        return rows.stream().findFirst();
    }

    @Override
    public void record(IdempotencyRecord record) {
        try {
            jdbc.sql("""
                            INSERT INTO idempotency_keys
                              (actor_id, operation, idem_key, request_hash, response_status,
                               resource_id, resource_version, created_at, expires_at)
                            VALUES (:actor, :operation, :key, :hash, :status, :resource, :version, :created, :expires)
                            """)
                    .param("actor", record.scope().actor().value())
                    .param("operation", record.scope().operation())
                    .param("key", record.scope().key().value())
                    .param("hash", record.requestHash())
                    .param("status", record.responseStatus())
                    .param("resource", record.resourceId().toString())
                    .param("version", record.resourceVersion())
                    .param("created", Timestamps.toColumn(record.createdAt()))
                    .param("expires", Timestamps.toColumn(record.expiresAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateIdempotencyKeyException();
        }
    }

    @Override
    public void deleteIfExpired(IdempotencyScope scope, Instant now) {
        jdbc.sql("""
                        DELETE FROM idempotency_keys
                         WHERE actor_id = :actor AND operation = :operation AND idem_key = :key
                           AND expires_at <= :now
                        """)
                .param("actor", scope.actor().value())
                .param("operation", scope.operation())
                .param("key", scope.key().value())
                .param("now", Timestamps.toColumn(now))
                .update();
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE expires_at <= :now ORDER BY expires_at LIMIT :limit")
                .param("now", Timestamps.toColumn(now))
                .param("limit", limit)
                .update();
    }
}
