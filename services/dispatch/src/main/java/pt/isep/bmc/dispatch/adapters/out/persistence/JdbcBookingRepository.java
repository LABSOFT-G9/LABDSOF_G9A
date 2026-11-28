package pt.isep.bmc.dispatch.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.dispatch.application.port.out.BookingRepository;
import pt.isep.bmc.dispatch.domain.booking.Booking;
import pt.isep.bmc.dispatch.domain.booking.BookingAction;
import pt.isep.bmc.dispatch.domain.booking.BookingId;
import pt.isep.bmc.dispatch.domain.booking.BookingSource;
import pt.isep.bmc.dispatch.domain.booking.BookingState;
import pt.isep.bmc.dispatch.domain.booking.ManualConfirmation;
import pt.isep.bmc.dispatch.domain.booking.ManualSource;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * Bookings through plain SQL, which keeps the worker's {@code FOR UPDATE SKIP
 * LOCKED} explicit (as the projections and idempotency keys are). An update
 * is written only over the version it was read at.
 */
@Repository
class JdbcBookingRepository implements BookingRepository {

    private static final String COLUMNS = """
            id, intervention_id, contractor_ref, building_code, summary, window_start, window_end, state, source,
            external_ref, reason_code, pending_action, attempts, next_attempt_at, last_reconciled_at,
            manual_window_start, manual_window_end, manual_source, manual_reason, manual_by, manual_at,
            version, created_at, updated_at""";

    private final JdbcClient jdbc;

    JdbcBookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(Booking booking) {
        jdbc.sql("INSERT INTO bookings (" + COLUMNS + ") VALUES (:id, :interventionId, :contractorRef, :buildingCode,"
                        + " :summary, :windowStart, :windowEnd, :state, :source, :externalRef, :reasonCode, :action,"
                        + " :attempts, :nextAttemptAt, :lastReconciledAt, :manualStart, :manualEnd, :manualSource,"
                        + " :manualReason, :manualBy, :manualAt, :version, :createdAt, :updatedAt)")
                .params(params(booking))
                .update();
    }

    @Override
    public Optional<Booking> find(BookingId id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM bookings WHERE id = :id").param("id", id.toString())
                .query(JdbcBookingRepository::row).optional();
    }

    @Override
    public Optional<Booking> findForUpdate(BookingId id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM bookings WHERE id = :id FOR UPDATE").param("id", id.toString())
                .query(JdbcBookingRepository::row).optional();
    }

    @Override
    public Optional<Booking> findLatestForUpdate(InterventionId interventionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM bookings WHERE intervention_id = :intervention"
                        + " ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE")
                .param("intervention", interventionId.toString())
                .query(JdbcBookingRepository::row).optional();
    }

    @Override
    public void update(Booking booking) {
        int updated = jdbc.sql("""
                        UPDATE bookings
                           SET state = :state, source = :source, external_ref = :externalRef, reason_code = :reasonCode,
                               pending_action = :action, attempts = :attempts, next_attempt_at = :nextAttemptAt,
                               last_reconciled_at = :lastReconciledAt, manual_window_start = :manualStart,
                               manual_window_end = :manualEnd, manual_source = :manualSource,
                               manual_reason = :manualReason, manual_by = :manualBy, manual_at = :manualAt,
                               version = :version, updated_at = :updatedAt
                         WHERE id = :id AND version = :previousVersion
                        """)
                .params(params(booking))
                .param("previousVersion", booking.version() - 1)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("booking changed since it was read");
        }
    }

    @Override
    public Optional<Booking> lockNextDue(Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM bookings WHERE next_attempt_at <= :now"
                        + " ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED")
                .param("now", Timestamps.toColumn(now))
                .query(JdbcBookingRepository::row).optional();
    }

    @Override
    public void logCall(BookingId id, BookingAction action, String outcome, Instant at) {
        jdbc.sql("INSERT INTO booking_calls (booking_id, action, outcome, occurred_at) VALUES (:id, :action, :outcome, :at)")
                .param("id", id.toString())
                .param("action", action.value())
                .param("outcome", outcome)
                .param("at", Timestamps.toColumn(at))
                .update();
    }

    private static Map<String, Object> params(Booking b) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", b.id().toString());
        p.put("interventionId", b.interventionId().toString());
        p.put("contractorRef", b.contractorRef());
        p.put("buildingCode", b.buildingCode());
        p.put("summary", b.summary());
        p.put("windowStart", Timestamps.toColumn(b.window().start()));
        p.put("windowEnd", Timestamps.toColumn(b.window().end()));
        p.put("state", b.state().value());
        p.put("source", b.source().map(BookingSource::value).orElse(null));
        p.put("externalRef", b.externalRef().orElse(null));
        p.put("reasonCode", b.reasonCode().orElse(null));
        p.put("action", b.action().map(BookingAction::value).orElse(null));
        p.put("attempts", b.attempts());
        p.put("nextAttemptAt", b.nextAttemptAt().map(Timestamps::toColumn).orElse(null));
        p.put("lastReconciledAt", b.lastReconciledAt().map(Timestamps::toColumn).orElse(null));
        p.put("manualStart", b.manual().map(m -> Timestamps.toColumn(m.window().start())).orElse(null));
        p.put("manualEnd", b.manual().map(m -> Timestamps.toColumn(m.window().end())).orElse(null));
        p.put("manualSource", b.manual().map(m -> m.source().value()).orElse(null));
        p.put("manualReason", b.manual().map(ManualConfirmation::reason).orElse(null));
        p.put("manualBy", b.manual().map(m -> m.confirmedBy().value()).orElse(null));
        p.put("manualAt", b.manual().map(m -> Timestamps.toColumn(m.confirmedAt())).orElse(null));
        p.put("version", b.version());
        p.put("createdAt", Timestamps.toColumn(b.createdAt()));
        p.put("updatedAt", Timestamps.toColumn(b.updatedAt()));
        return p;
    }

    private static Booking row(ResultSet rs, int n) throws SQLException {
        Optional<ManualConfirmation> manual = time(rs, "manual_at").map(at -> {
            try {
                return new ManualConfirmation(
                        new TimeWindow(time(rs, "manual_window_start").orElseThrow(),
                                time(rs, "manual_window_end").orElseThrow()),
                        ManualSource.fromValue(rs.getString("manual_source")),
                        rs.getString("manual_reason"),
                        new UserId(rs.getString("manual_by")),
                        at);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        return new Booking(
                new BookingId(UUID.fromString(rs.getString("id"))),
                new InterventionId(UUID.fromString(rs.getString("intervention_id"))),
                rs.getString("contractor_ref"),
                rs.getString("building_code"),
                rs.getString("summary"),
                new TimeWindow(time(rs, "window_start").orElseThrow(), time(rs, "window_end").orElseThrow()),
                BookingState.fromValue(rs.getString("state")),
                Optional.ofNullable(rs.getString("source")).map(BookingSource::fromValue),
                Optional.ofNullable(rs.getString("external_ref")),
                Optional.ofNullable(rs.getString("reason_code")),
                Optional.ofNullable(rs.getString("pending_action")).map(BookingAction::fromValue),
                rs.getInt("attempts"),
                time(rs, "next_attempt_at"),
                time(rs, "last_reconciled_at"),
                manual,
                rs.getLong("version"),
                time(rs, "created_at").orElseThrow(),
                time(rs, "updated_at").orElseThrow());
    }

    private static Optional<Instant> time(ResultSet rs, String column) throws SQLException {
        return Optional.ofNullable(rs.getObject(column, LocalDateTime.class)).map(Timestamps::fromColumn);
    }
}
