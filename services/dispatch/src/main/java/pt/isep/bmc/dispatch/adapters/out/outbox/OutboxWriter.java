package pt.isep.bmc.dispatch.adapters.out.outbox;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import pt.isep.bmc.dispatch.application.port.out.EventOutbox;
import pt.isep.bmc.dispatch.application.port.out.OutboxEvent;
import pt.isep.bmc.dispatch.domain.event.BookingConfirmed;
import pt.isep.bmc.dispatch.domain.event.BookingStateChanged;
import pt.isep.bmc.dispatch.domain.event.CompletionReported;
import pt.isep.bmc.dispatch.domain.event.ContractorResponded;
import pt.isep.bmc.dispatch.domain.event.DomainEvent;
import pt.isep.bmc.dispatch.domain.event.InterventionApproved;
import pt.isep.bmc.dispatch.domain.event.InterventionCancellationPending;
import pt.isep.bmc.dispatch.domain.event.InterventionCancelled;
import pt.isep.bmc.dispatch.domain.event.InterventionDeclined;
import pt.isep.bmc.dispatch.domain.event.InterventionStarted;
import pt.isep.bmc.dispatch.domain.event.InterventionTermsChanged;
import pt.isep.bmc.dispatch.domain.event.ReworkRequested;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes each event as a CloudEvents 1.0 structured JSON message in a
 * {@code pending} outbox row, in the caller's transaction (ADR-003, technical
 * design 4.4), as Issue does. Routing key {@code dispatch.<eventType>};
 * {@code data} follows the proto3 JSON mapping of {@code
 * contracts/proto/bmc/dispatch/v1}.
 *
 * <p>The publisher that sends pending rows to {@code bmc.events} with
 * publisher confirms is the messaging starter's, wired in EN-02-02 (#78).
 * Events carry codes and IDs only, never the scope, terms or a contractor's
 * note.
 */
@Component
class OutboxWriter implements EventOutbox {

    static final String SOURCE = "dispatch";
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
            case InterventionApproved e -> {
                data.put("issueId", e.issueId().toString());
                data.put("contractorUserId", e.contractorUserId().value());
                data.put("contractorDisplayName", e.contractorDisplayName());
                data.put("approvedProposalVersion", e.approvedProposalVersion());
                data.put("interventionState", e.interventionState().value());
            }
            case ContractorResponded e -> {
                data.put("issueId", e.issueId().toString());
                data.put("decision", e.decision().value());
                data.put("proposalVersion", e.proposalVersion());
                data.put("interventionState", e.interventionState().value());
            }
            case BookingConfirmed e -> {
                data.put("issueId", e.issueId().toString());
                data.put("window", Map.of("start", DateTimeFormatter.ISO_INSTANT.format(e.window().start()),
                        "end", DateTimeFormatter.ISO_INSTANT.format(e.window().end())));
                data.put("source", e.source().value());
                data.put("interventionState", e.interventionState().value());
            }
            case BookingStateChanged e -> {
                data.put("issueId", e.issueId().toString());
                data.put("bookingState", e.bookingState().value());
                data.put("interventionState", e.interventionState().value());
            }
            case InterventionCancellationPending e -> {
                data.put("issueId", e.issueId().toString());
                data.put("reasonCode", e.reasonCode().value());
                data.put("interventionState", e.interventionState().value());
            }
            case InterventionCancelled e -> {
                data.put("issueId", e.issueId().toString());
                data.put("reasonCode", e.reasonCode().value());
                data.put("interventionState", e.interventionState().value());
            }
            case InterventionStarted e -> state(data, e.issueId().toString(), e.interventionState().value());
            case CompletionReported e -> state(data, e.issueId().toString(), e.interventionState().value());
            case ReworkRequested e -> state(data, e.issueId().toString(), e.interventionState().value());
            case InterventionDeclined e -> {
                data.put("issueId", e.issueId().toString());
                data.put("reasonCode", e.reasonCode().value());
                data.put("interventionState", e.interventionState().value());
            }
            case InterventionTermsChanged e -> {
                data.put("issueId", e.issueId().toString());
                data.put("proposalVersion", e.proposalVersion());
                data.put("supersededProposalVersion", e.supersededProposalVersion());
                data.put("supersededAuthor", e.supersededAuthor());
                data.put("previousState", e.previousState().value());
                e.contractorUserId().ifPresent(user -> data.put("contractorUserId", user.value()));
                data.put("interventionState", e.interventionState().value());
            }
        }
        return data;
    }

    private static void state(Map<String, Object> data, String issueId, String interventionState) {
        data.put("issueId", issueId);
        data.put("interventionState", interventionState);
    }

    private static LocalDateTime column(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
