package pt.isep.bmc.issue.adapters.out.outbox;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import pt.isep.bmc.issue.application.port.out.EventOutbox;
import pt.isep.bmc.issue.application.port.out.OutboxEvent;
import pt.isep.bmc.issue.domain.event.DomainEvent;
import pt.isep.bmc.issue.domain.event.IssueStateChanged;
import pt.isep.bmc.issue.domain.event.IssueSummaryPublished;
import pt.isep.bmc.issue.domain.event.IssueTriaged;
import pt.isep.bmc.issue.domain.event.ReportSubmitted;
import pt.isep.bmc.issue.domain.event.TriageRequested;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;
import pt.isep.bmc.issue.domain.issue.SummaryId;
import pt.isep.bmc.issue.domain.issue.TriageDecision;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes each event as a CloudEvents 1.0 structured JSON message in a
 * {@code pending} outbox row, in the caller's transaction (ADR-003, technical
 * design 4.4). Routing key {@code issue.<eventType>}.
 *
 * <p>The publisher that sends pending rows to {@code bmc.events} with publisher
 * confirms belongs to the shared messaging starter of EN-02-02 (#78). The
 * {@code data} layout follows technical design 4.4 and will be aligned with the
 * {@code .proto} schemas when {@code contracts/proto} exists. Payloads are never
 * logged: {@code TriageRequested} carries report text.
 */
@Component
class OutboxWriter implements EventOutbox {

    static final String SOURCE = "issue";
    static final int SCHEMA_VERSION = 1;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void append(OutboxEvent outboxEvent) {
        DomainEvent event = outboxEvent.event();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", outboxEvent.eventId().toString());
        envelope.put("type", event.type());
        envelope.put("source", SOURCE);
        envelope.put("subject", event.aggregateId().toString());
        envelope.put("time", DateTimeFormatter.ISO_INSTANT.format(event.occurredAt()));
        envelope.put("datacontenttype", "application/json");
        envelope.put("schemaversion", SCHEMA_VERSION);
        envelope.put("aggregateversion", event.aggregateVersion());
        envelope.put("buildingid", event.buildingId().toString());
        envelope.put("correlationid", outboxEvent.correlationId());
        envelope.put("data", data(event));

        jdbc.sql("""
                        INSERT INTO outbox
                          (id, event_type, routing_key, aggregate_id, aggregate_version, building_id,
                           correlation_id, payload, status, attempts, created_at)
                        VALUES (:id, :type, :routingKey, :aggregateId, :aggregateVersion, :buildingId,
                                :correlationId, :payload, 'pending', 0, :createdAt)
                        """)
                .param("id", outboxEvent.eventId().toString())
                .param("type", event.type())
                .param("routingKey", SOURCE + "." + event.type())
                .param("aggregateId", event.aggregateId().toString())
                .param("aggregateVersion", event.aggregateVersion())
                .param("buildingId", event.buildingId().toString())
                .param("correlationId", outboxEvent.correlationId())
                .param("payload", json.writeValueAsString(envelope))
                .param("createdAt", column(event.occurredAt()))
                .update();
    }

    private static Map<String, Object> data(DomainEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        switch (event) {
            case ReportSubmitted e -> {
                data.put("reportId", e.reportId().toString());
                data.put("issueId", e.issueId().toString());
                data.put("areaId", e.areaId().toString());
            }
            case IssueTriaged e -> {
                data.put("issueId", e.issueId().toString());
                data.put("category", e.category().value());
                data.put("urgency", e.urgency().value());
                data.put("decisionSource", TriageDecision.SOURCE_HUMAN);
            }
            case IssueStateChanged e -> {
                data.put("issueId", e.issueId().toString());
                data.put("buildingId", e.buildingId().toString());
                data.put("state", e.state().value());
                data.put("version", e.issueVersion());
                data.put("dispatchable", e.dispatchable());
                // Undecided values are sent as null, never as a made-up default.
                data.put("category", e.category().map(IssueCategory::value).orElse(null));
                data.put("urgency", e.urgency().map(IssueUrgency::value).orElse(null));
                data.put("areaLabel", e.areaLabel());
                data.put("publishedSummaryId", e.publishedSummaryId().map(SummaryId::toString).orElse(null));
            }
            case IssueSummaryPublished e -> {
                data.put("issueId", e.issueId().toString());
                data.put("summaryId", e.summaryId().toString());
            }
            case TriageRequested e -> {
                data.put("issueId", e.issueId().toString());
                data.put("issueVersion", e.issueVersion());
                data.put("buildingId", e.buildingId().toString());
                data.put("areaKind", e.areaKind().value());
                data.put("text", e.text().value());
            }
        }
        return data;
    }

    private static LocalDateTime column(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
