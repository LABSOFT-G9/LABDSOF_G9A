package pt.isep.bmc.issue.adapters.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pt.isep.bmc.issue.application.command.ApplyDispatchEventCommand;
import pt.isep.bmc.issue.domain.dispatch.DispatchEvent;
import pt.isep.bmc.issue.domain.dispatch.DispatchEventType;
import pt.isep.bmc.issue.domain.dispatch.InterventionState;
import pt.isep.bmc.issue.domain.shared.FieldViolation;
import pt.isep.bmc.issue.domain.shared.ValidationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * US-07-03: reading Dispatch's events as Dispatch's outbox writes them
 * ({@code services/dispatch/.../adapters/out/outbox/OutboxWriter}): the
 * CloudEvents attributes, and {@code data} in camelCase.
 */
class DispatchEventsTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String INTERVENTION = "0199f2a0-0000-7000-8000-0000000d0001";
    static final String ISSUE = "0199f2a0-0000-7000-8000-0000000e1001";
    static final String BUILDING = "0199f2a0-0000-7000-8000-00000000b001";

    static ObjectNode envelope(String type, String data) {
        return (ObjectNode) JSON.readTree("""
                {"specversion": "1.0", "id": "0199f2a0-0000-7000-8000-0000000f0001", "type": "%s",
                 "source": "/dispatch", "subject": "%s", "time": "2026-11-28T10:00:00.123Z",
                 "datacontenttype": "application/json", "schemaversion": 1, "aggregateversion": 3,
                 "buildingid": "%s", "correlationid": "corr-1", "data": %s}
                """.formatted(type, INTERVENTION, BUILDING, data));
    }

    static String data(String extra) {
        return "{\"issueId\": \"" + ISSUE + "\", \"interventionState\": \"sent\"" + extra + "}";
    }

    static DispatchEvent parse(JsonNode envelope) {
        return DispatchEvents.parse(envelope).event();
    }

    static java.util.List<FieldViolation> violations(JsonNode envelope) {
        try {
            DispatchEvents.parse(envelope);
        } catch (ValidationException invalid) {
            return invalid.violations();
        }
        throw new AssertionError("expected the message to be refused");
    }

    @Test
    void readsTheEnvelope() {
        ApplyDispatchEventCommand command = DispatchEvents.parse(envelope("InterventionApproved",
                data(", \"contractorUserId\": \"0199f2a0-0000-7000-8000-000000000301\","
                        + " \"contractorDisplayName\": \"Synthetic Plumbing Co.\", \"approvedProposalVersion\": 1")));
        DispatchEvent event = command.event();

        assertThat(event.eventId()).isEqualTo("0199f2a0-0000-7000-8000-0000000f0001");
        assertThat(event.type()).isEqualTo(DispatchEventType.INTERVENTION_APPROVED);
        assertThat(event.interventionId()).hasToString(INTERVENTION);
        assertThat(event.aggregateVersion()).isEqualTo(3);
        assertThat(event.buildingId().value()).hasToString(BUILDING);
        assertThat(event.issueId().value()).hasToString(ISSUE);
        assertThat(event.interventionState()).isEqualTo(InterventionState.SENT);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-11-28T10:00:00.123Z"));
        assertThat(event.contractorUserId()).contains("0199f2a0-0000-7000-8000-000000000301");
        assertThat(command.correlationId()).isEqualTo("corr-1");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', textBlock = """
            ContractorResponded             | , "decision": "accept", "proposalVersion": 2              | accept
            ContractorResponded             | , "decision": "counterpropose", "proposalVersion": 2      | counterpropose
            InterventionDeclined            | , "reasonCode": "unavailable"                             | unavailable
            InterventionCancellationPending | , "reasonCode": "rescheduling"                            | rescheduling
            InterventionCancelled           | , "reasonCode": "issue_resolved"                          | issue_resolved
            BookingStateChanged             | , "bookingState": "uncertain"                             | uncertain
            InterventionStarted             |                                                           |
            CompletionReported              |                                                           |
            ReworkRequested                 |                                                           |
            InterventionTermsChanged        | , "proposalVersion": 3, "supersededProposalVersion": 2, "supersededAuthor": "administrator", "previousState": "sent" |
            """)
    void readsEachEventsCode(String type, String extra, String code) {
        DispatchEvent event = parse(envelope(type, data(extra == null ? "" : extra)));

        assertThat(event.type().value()).isEqualTo(type);
        assertThat(event.code()).isEqualTo(java.util.Optional.ofNullable(code));
        assertThat(event.contractorUserId()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"calendar, false", "manual, true"})
    void readsABookingsWindowAndSource(String source, boolean manual) {
        DispatchEvent event = parse(envelope("BookingConfirmed", data(", \"window\": {\"start\":"
                + " \"2026-12-02T09:00:00Z\", \"end\": \"2026-12-02T12:00:00Z\"}, \"source\": \"" + source + "\"")));

        assertThat(event.window()).hasValueSatisfying(window -> {
            assertThat(window.start()).isEqualTo(Instant.parse("2026-12-02T09:00:00Z"));
            assertThat(window.end()).isEqualTo(Instant.parse("2026-12-02T12:00:00Z"));
        });
        assertThat(event.manual()).isEqualTo(manual);
    }

    @Test
    void ignoresFieldsItDoesNotKnow() {
        ObjectNode message = envelope("InterventionStarted", data(", \"somethingNew\": {\"a\": 1}"));
        message.put("extension", "x");

        assertThat(parse(message).type()).isEqualTo(DispatchEventType.INTERVENTION_STARTED);
    }

    @Test
    void refusesAnotherSchemaVersion() {
        ObjectNode message = envelope("InterventionStarted", data(""));
        message.put("schemaversion", 2);

        assertThat(violations(message)).containsExactly(new FieldViolation("schemaversion", "unsupported_version"));
    }

    @Test
    void namesEveryInvalidFieldAndWhyButNoValue() {
        ObjectNode message = envelope("InterventionApproved",
                "{\"issueId\": \"not-a-uuid\", \"interventionState\": \"teleported\"}");
        message.put("subject", "1-2-3-4-5");
        message.put("aggregateversion", 0);
        message.put("time", "yesterday");
        message.remove("buildingid");

        java.util.List<FieldViolation> violations = violations(message);

        assertThat(violations).containsExactlyInAnyOrder(
                new FieldViolation("subject", "invalid_format"),
                new FieldViolation("time", "invalid_format"),
                new FieldViolation("aggregateversion", "out_of_range"),
                new FieldViolation("buildingid", "required"),
                new FieldViolation("data.issueId", "invalid_format"),
                new FieldViolation("data.interventionState", "unknown_value"),
                new FieldViolation("data.contractorUserId", "required"));
        assertThat(violations.toString()).doesNotContain("not-a-uuid", "teleported", "yesterday");
    }

    @Test
    void refusesWhatEachTypeNeeds() {
        assertThat(violations(envelope("ContractorResponded", data(", \"decision\": \"decline\""))))
                .containsExactly(new FieldViolation("data.decision", "unknown_value"));
        assertThat(violations(envelope("InterventionDeclined", data(", \"reasonCode\": \"Not Free Text\""))))
                .containsExactly(new FieldViolation("data.reasonCode", "invalid_format"));
        assertThat(violations(envelope("BookingStateChanged", data(", \"bookingState\": \"confirmed\""))))
                .containsExactly(new FieldViolation("data.bookingState", "unknown_value"));
        assertThat(violations(envelope("BookingConfirmed", data(", \"window\": {\"start\": \"2026-12-02T12:00:00Z\","
                + " \"end\": \"2026-12-02T09:00:00Z\"}, \"source\": \"phone\""))))
                .containsExactlyInAnyOrder(new FieldViolation("data.window.end", "not_after_start"),
                        new FieldViolation("data.source", "unknown_value"));
        assertThat(violations(envelope("InterventionMerged", data(""))))
                .containsExactly(new FieldViolation("type", "unknown_value"));
    }

    @Test
    void refusesAMessageWithoutData() {
        ObjectNode message = envelope("InterventionStarted", data(""));
        message.remove("data");

        assertThat(violations(message)).contains(new FieldViolation("data", "required"));
        assertThatThrownBy(() -> DispatchEvents.parse(JSON.readTree("[]"))).isInstanceOf(ValidationException.class);
    }

    @Test
    void aVersionWrittenAsTextIsNotANumber() {
        ObjectNode message = envelope("InterventionStarted", data(""));
        message.put("aggregateversion", "3");

        assertThat(violations(message)).containsExactly(new FieldViolation("aggregateversion", "invalid_type"));
    }
}
