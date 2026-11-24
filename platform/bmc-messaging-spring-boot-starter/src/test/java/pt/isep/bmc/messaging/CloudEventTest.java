package pt.isep.bmc.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class CloudEventTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The IssueStateChanged example of the contract annex (07a, 1.3). */
    private static final String EXAMPLE = """
            { "specversion": "1.0",
              "id": "0199f2a1-0000-7000-8000-000000006003",
              "type": "IssueStateChanged",
              "source": "issue",
              "subject": "0199f2a1-0000-7000-8000-000000002001",
              "time": "2026-11-03T09:40:27Z",
              "datacontenttype": "application/json",
              "schemaversion": 1,
              "aggregateversion": 3,
              "buildingid": "0199f2a0-0000-7000-8000-00000000b001",
              "correlationid": "c-7f9d",
              "data": { "issueId": "0199f2a1-0000-7000-8000-000000002001", "state": "triaged", "version": 3 } }
            """;

    private static CloudEvent parse(String body) {
        return CloudEvent.parse(body.getBytes(StandardCharsets.UTF_8), JSON);
    }

    @Test
    void readsTheEnvelopeOfTheContractAnnex() {
        CloudEvent event = parse(EXAMPLE);

        assertThat(event.id()).isEqualTo("0199f2a1-0000-7000-8000-000000006003");
        assertThat(event.type()).isEqualTo("IssueStateChanged");
        assertThat(event.source()).isEqualTo("issue");
        assertThat(event.subject()).isEqualTo("0199f2a1-0000-7000-8000-000000002001");
        assertThat(event.time()).isEqualTo(Instant.parse("2026-11-03T09:40:27Z"));
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.aggregateVersion()).isEqualTo(3);
        assertThat(event.buildingId()).isEqualTo("0199f2a0-0000-7000-8000-00000000b001");
        assertThat(event.correlationId()).isEqualTo("c-7f9d");
        assertThat(event.traceparent()).isNull();
        assertThat(JSON.readTree(event.dataJson()).get("state").stringValue()).isEqualTo("triaged");
    }

    @Test
    void readsTheOptionalTraceContext() {
        CloudEvent event = parse(EXAMPLE.replace("\"correlationid\"", "\"traceparent\": \"00-a-b-01\", \"correlationid\""));

        assertThat(event.traceparent()).isEqualTo("00-a-b-01");
    }

    @Test
    void neverPutsThePayloadInItsText() {
        assertThat(parse(EXAMPLE).toString()).contains("IssueStateChanged").doesNotContain("triaged");
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "type", "source", "subject", "time", "schemaversion", "aggregateversion",
        "buildingid", "correlationid", "data", "specversion"})
    void refusesAnEnvelopeWithoutARequiredAttribute(String attribute) {
        String body = EXAMPLE.replace("\"" + attribute + "\"", "\"x-" + attribute + "\"");

        assertThatThrownBy(() -> parse(body)).isInstanceOf(InvalidEventException.class)
                .hasMessageContaining(attribute);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not json",
        "[]",
        "\"text\"",
    })
    void refusesABodyThatIsNotAnEnvelope(String body) {
        assertThatThrownBy(() -> parse(body)).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void refusesWrongTypesAndValues() {
        assertThatThrownBy(() -> parse(EXAMPLE.replace("\"schemaversion\": 1", "\"schemaversion\": \"1\"")))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("schemaversion");
        assertThatThrownBy(() -> parse(EXAMPLE.replace("\"aggregateversion\": 3", "\"aggregateversion\": -1")))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("aggregateversion");
        assertThatThrownBy(() -> parse(EXAMPLE.replace("\"specversion\": \"1.0\"", "\"specversion\": \"0.3\"")))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("specversion");
        assertThatThrownBy(() -> parse(EXAMPLE.replace("2026-11-03T09:40:27Z", "yesterday")))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("time");
        assertThatThrownBy(() -> parse(EXAMPLE.replace("\"type\": \"IssueStateChanged\"", "\"type\": 7")))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("type");
    }

    @Test
    void errorsNeverQuoteThePayload() {
        String body = EXAMPLE.replace("\"schemaversion\": 1", "\"schemaversion\": \"1\"")
                .replace("triaged", "PRIVATE-REPORT-TEXT");

        assertThatThrownBy(() -> parse(body)).isInstanceOf(InvalidEventException.class)
                .hasMessageNotContaining("PRIVATE-REPORT-TEXT");
    }
}
