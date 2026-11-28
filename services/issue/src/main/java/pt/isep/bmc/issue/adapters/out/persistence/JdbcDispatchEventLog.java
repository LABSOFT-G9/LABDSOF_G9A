package pt.isep.bmc.issue.adapters.out.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.issue.application.port.out.DispatchEventLog;
import pt.isep.bmc.issue.domain.dispatch.DispatchEvent;
import pt.isep.bmc.issue.domain.dispatch.DispatchEventRecord;
import pt.isep.bmc.issue.domain.dispatch.VisitWindow;

/** Received Dispatch events in {@code dispatch_events}. Plain SQL in the caller's transaction. */
@Repository
class JdbcDispatchEventLog implements DispatchEventLog {

    private final JdbcClient jdbc;

    JdbcDispatchEventLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(String eventId) {
        return jdbc.sql("SELECT COUNT(*) FROM dispatch_events WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(Long.class)
                .single() > 0;
    }

    @Override
    public void add(DispatchEventRecord record) {
        DispatchEvent event = record.event();
        jdbc.sql("""
                        INSERT INTO dispatch_events (event_id, event_type, issue_id, intervention_id, aggregate_version,
                                                     intervention_state, outcome, reason, history_sequence, code,
                                                     manual, window_start, window_end, occurred_at, received_at)
                        VALUES (:eventId, :type, :issueId, :interventionId, :version,
                                :state, :outcome, :reason, :sequence, :code,
                                :manual, :windowStart, :windowEnd, :occurredAt, :receivedAt)
                        ON DUPLICATE KEY UPDATE event_id = event_id
                        """)
                .param("eventId", event.eventId())
                .param("type", event.type().value())
                .param("issueId", event.issueId().toString())
                .param("interventionId", event.interventionId().toString())
                .param("version", event.aggregateVersion())
                .param("state", event.interventionState().value())
                .param("outcome", record.outcome().value())
                .param("reason", record.reason().orElse(null))
                .param("sequence", record.historySequence().orElse(null))
                .param("code", event.code().orElse(null))
                .param("manual", event.manual())
                .param("windowStart", Timestamps.toColumn(event.window().map(VisitWindow::start).orElse(null)))
                .param("windowEnd", Timestamps.toColumn(event.window().map(VisitWindow::end).orElse(null)))
                .param("occurredAt", Timestamps.toColumn(event.occurredAt()))
                .param("receivedAt", Timestamps.toColumn(record.receivedAt()))
                .update();
    }
}
