package pt.isep.bmc.notification.adapters.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import pt.isep.bmc.notification.adapters.in.messaging.NotificationRequestedHandler.Outcome;
import pt.isep.bmc.notification.application.InMemoryPorts;
import pt.isep.bmc.notification.application.command.NotificationFacade;
import pt.isep.bmc.notification.application.error.StorageUnavailableException;
import pt.isep.bmc.notification.application.port.out.TransactionConflictException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class NotificationRequestedHandlerTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String ISSUE = "0199f2a1-0000-7000-8000-000000002001";
    static final EventEnvelope ENVELOPE = new EventEnvelope("0199f2a1-0000-7000-8000-000000006003", "c-7f9d");

    final InMemoryPorts ports = new InMemoryPorts();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final NotificationFacade receive = ports.facade(Clock.systemUTC());

    NotificationRequestedHandler handler(int maxAttempts) {
        return new NotificationRequestedHandler(receive, meters, maxAttempts, Duration.ZERO);
    }

    @Test
    void aValidRequestIsDeliveredAndARedeliveryIsADuplicate() {
        JsonNode data = data(UUID.randomUUID().toString(), Map.of("issueId", ISSUE));
        assertThat(handler(5).handle(ENVELOPE, data)).isEqualTo(Outcome.DELIVERED);
        assertThat(handler(5).handle(ENVELOPE, data)).isEqualTo(Outcome.DUPLICATE);
        assertThat(ports.inbox).hasSize(1);
        assertThat(meters.counter("bmc.notification.delivered").count()).isEqualTo(1);
        assertThat(meters.counter("bmc.notification.requests", "outcome", "duplicate").count()).isEqualTo(1);
    }

    @Test
    void anInvalidRequestIsRefusedAtOnceAndLoggedWithItsIdsButNoValues(CapturedOutput output) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("issueId", ISSUE);
        params.put("note", "PrivateMarker Jane in flat 2B");
        assertThat(handler(5).handle(ENVELOPE, data(UUID.randomUUID().toString(), params)))
                .isEqualTo(Outcome.REJECTED);
        assertThat(ports.transactionsStarted).isZero();
        assertThat(ports.inbox).isEmpty();
        assertThat(meters.counter("bmc.notification.requests", "outcome", "rejected").count()).isEqualTo(1);
        assertThat(output.getAll())
                .contains(ENVELOPE.eventId(), "c-7f9d", "params.note: not_allowed")
                .doesNotContain("PrivateMarker", "Jane");
    }

    @Test
    void aStorageFailureIsRetriedAndThenSucceeds() {
        ports.inApp.failDelivery = 1;
        assertThat(handler(5).handle(ENVELOPE, data(UUID.randomUUID().toString(), Map.of("issueId", ISSUE))))
                .isEqualTo(Outcome.DELIVERED);
        assertThat(ports.inbox).hasSize(1);
    }

    @Test
    void retriesAreLimitedAndTheirEndIsLoggedWithTheIds(CapturedOutput output) {
        StorageUnavailableException down = new StorageUnavailableException(new IllegalStateException("down"));
        NotificationRequestedHandler handler = new NotificationRequestedHandler(request -> {
            throw down;
        }, meters, 3, Duration.ZERO);
        assertThat(handler.handle(ENVELOPE, data(UUID.randomUUID().toString(), Map.of("issueId", ISSUE))))
                .isEqualTo(Outcome.EXHAUSTED);
        assertThat(meters.counter("bmc.notification.requests", "outcome", "exhausted").count()).isEqualTo(1);
        assertThat(output.getAll())
                .contains("not stored after 3 attempts", ENVELOPE.eventId(), "c-7f9d",
                        StorageUnavailableException.class.getName())
                .contains("attempt 1 of 3", "attempt 2 of 3")
                .doesNotContain("attempt 3 of 3");
    }

    @Test
    void aDefectIsNotRetried(CapturedOutput output) {
        int[] calls = new int[1];
        NotificationRequestedHandler handler = new NotificationRequestedHandler(request -> {
            calls[0]++;
            throw new IllegalStateException("a bug");
        }, meters, 5, Duration.ZERO);
        assertThat(handler.handle(ENVELOPE, data(UUID.randomUUID().toString(), Map.of("issueId", ISSUE))))
                .isEqualTo(Outcome.EXHAUSTED);
        assertThat(calls[0]).isEqualTo(1);
        assertThat(output.getAll()).contains("which a retry cannot fix", ENVELOPE.eventId(),
                IllegalStateException.class.getName()).doesNotContain("a bug");
    }

    @Test
    void aConflictTheFacadeCouldNotResolveIsNotRetriedAgain(CapturedOutput output) {
        // The facade retries conflicts itself (NotificationFacadeTest); one that reaches the handler has used them up.
        int[] calls = new int[1];
        NotificationRequestedHandler handler = new NotificationRequestedHandler(request -> {
            calls[0]++;
            throw new TransactionConflictException(new IllegalStateException("deadlock"));
        }, meters, 5, Duration.ZERO);
        assertThat(handler.handle(ENVELOPE, data(UUID.randomUUID().toString(), Map.of("issueId", ISSUE))))
                .isEqualTo(Outcome.EXHAUSTED);
        assertThat(calls[0]).isEqualTo(1);
        assertThat(output.getAll()).contains("still conflicted", ENVELOPE.eventId(), "c-7f9d");
    }

    @Test
    void anInterruptStopsTheRetriesAndLeavesTheMessageForRedelivery(CapturedOutput output) {
        int[] calls = new int[1];
        NotificationRequestedHandler handler = new NotificationRequestedHandler(request -> {
            calls[0]++;
            Thread.currentThread().interrupt(); // the service is shutting down during this attempt
            throw new StorageUnavailableException(new IllegalStateException("down"));
        }, meters, 5, Duration.ofSeconds(2));
        try {
            assertThat(handler.handle(ENVELOPE, data(UUID.randomUUID().toString(), Map.of("issueId", ISSUE))))
                    .isEqualTo(Outcome.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clear the flag for the other tests
        }
        assertThat(calls[0]).isEqualTo(1);
        assertThat(meters.counter("bmc.notification.requests", "outcome", "interrupted").count()).isEqualTo(1);
        assertThat(output.getAll()).contains("interrupted after attempt 1 of 5", ENVELOPE.eventId());
    }

    @Test
    void anEnvelopeCannotInjectTextIntoTheLogs() {
        EventEnvelope envelope = new EventEnvelope("id\nFAKE LOG LINE", "c 1");
        assertThat(envelope.eventId()).isEqualTo("unknown");
        assertThat(envelope.correlationId()).isEqualTo("unknown");
    }

    static JsonNode data(String notificationId, Map<String, String> params) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notificationId", notificationId);
        data.put("sourceEventId", UUID.randomUUID().toString());
        data.put("recipientUserIds", List.of("resident-1"));
        data.put("templateKey", "issue.resolved");
        data.put("params", params);
        data.put("priority", "normal");
        return JSON.valueToTree(data);
    }
}
