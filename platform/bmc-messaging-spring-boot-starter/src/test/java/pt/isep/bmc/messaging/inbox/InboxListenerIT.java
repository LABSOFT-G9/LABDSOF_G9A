package pt.isep.bmc.messaging.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import pt.isep.bmc.messaging.CloudEvent;
import pt.isep.bmc.messaging.MessagingMetrics;
import pt.isep.bmc.messaging.support.Stack;
import tools.jackson.databind.json.JsonMapper;

/** The idempotent consumer against a real MySQL 8.4 and a real RabbitMQ 4.4 quorum queue (NFR-04, ADR-003). */
class InboxListenerIT {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private final MessagingMetrics metrics = new MessagingMetrics(null);
    private final AtomicInteger calls = new AtomicInteger();
    private CachingConnectionFactory connectionFactory;
    private SimpleMessageListenerContainer container;
    private String source;
    private String queue;

    @BeforeEach
    void setUp() {
        Stack.resetDatabase();
        source = "p" + System.nanoTime();
        queue = Stack.declareQueue("inbox-it");
        Stack.bind(queue, source + ".#");
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    /** Starts a consumer of the test queue whose handler for {@code Noted} runs {@code effect}. */
    private void consume(Consumer<CloudEvent> effect) {
        EventHandler handler = new EventHandler() {
            @Override
            public String eventType() {
                return "Noted";
            }

            @Override
            public void handle(CloudEvent event) {
                calls.incrementAndGet();
                effect.accept(event);
            }
        };
        InboxListener listener = new InboxListener(List.of(handler), new ProcessedEventStore(Stack.jdbc()),
                Stack.transaction(), JsonMapper.builder().build(), metrics, Clock.systemUTC(), Set.of(1),
                Duration.ofMillis(20));
        connectionFactory = Stack.connectionFactory();
        container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queue);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(10);
        container.setAutoDeclare(false);
        container.setMissingQueuesFatal(false);
        container.setMessageListener(listener);
        container.start();
    }

    private static void recordEffect(CloudEvent event) {
        Stack.jdbc().sql("INSERT INTO effects (event_id, note) VALUES (:id, :note)")
                .param("id", event.id())
                .param("note", event.data().get("note").stringValue())
                .update();
    }

    private String send(String type, int schemaVersion) {
        String id = UUID.randomUUID().toString();
        Stack.publish(source + "." + type, Stack.envelope(id, source, type, schemaVersion, "{\"note\":\"hello\"}"));
        return id;
    }

    private Message deadLetter() {
        return Stack.receive(Stack.deadLetterQueue(queue), WAIT.toMillis());
    }

    @Test
    void appliesAnEventAndRecordsItsIdInTheSameTransaction() {
        consume(InboxListenerIT::recordEffect);

        String id = send("Noted", 1);

        await().atMost(WAIT).untilAsserted(() -> assertThat(metrics.processedCount()).isEqualTo(1));
        assertThat(Stack.count("effects")).isEqualTo(1);
        assertThat(Stack.jdbc().sql("SELECT event_type FROM processed_events WHERE event_id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("Noted");
        // Acknowledged: nothing is left in the queue.
        container.stop();
        assertThat(Stack.receive(queue, 500)).isNull();
    }

    @Test
    void aRepeatedDeliveryHasNoSecondEffect() {
        consume(InboxListenerIT::recordEffect);
        String id = UUID.randomUUID().toString();
        String body = Stack.envelope(id, source, "Noted", 1, "{\"note\":\"once\"}");

        Stack.publish(source + ".Noted", body);
        Stack.publish(source + ".Noted", body);
        Stack.publish(source + ".Noted", body);

        await().atMost(WAIT).untilAsserted(() -> assertThat(metrics.duplicateCount()).isEqualTo(2));
        assertThat(metrics.processedCount()).isEqualTo(1);
        assertThat(calls).hasValue(1);
        assertThat(Stack.count("effects")).isEqualTo(1);
        assertThat(Stack.count("processed_events")).isEqualTo(1);
    }

    @Test
    void aFailedEventRollsBackAndIsDeliveredAgain() {
        consume(event -> {
            recordEffect(event);
            if (calls.get() <= 2) {
                throw new IllegalStateException("the database hiccuped");
            }
        });

        send("Noted", 1);

        await().atMost(WAIT).untilAsserted(() -> assertThat(metrics.processedCount()).isEqualTo(1));
        // Two failures left nothing behind: one effect and one processed ID, from the third delivery.
        assertThat(calls).hasValue(3);
        assertThat(metrics.consumeFailureCount()).isEqualTo(2);
        assertThat(Stack.count("effects")).isEqualTo(1);
        assertThat(Stack.count("processed_events")).isEqualTo(1);
    }

    @Test
    void afterTheDeliveryLimitTheEventIsInTheDeadLetterQueue() {
        consume(event -> {
            recordEffect(event);
            throw new IllegalStateException("always fails");
        });

        String id = send("Noted", 1);

        Message dead = deadLetter();
        assertThat(dead).as("the event reaches the dead-letter queue").isNotNull();
        assertThat(new String(dead.getBody(), java.nio.charset.StandardCharsets.UTF_8)).contains(id);
        // The first delivery and the redeliveries the limit allows.
        assertThat(calls).hasValue(Stack.DELIVERY_LIMIT + 1);
        assertThat(Stack.count("effects")).isZero();
        assertThat(Stack.count("processed_events")).isZero();
    }

    @Test
    void anUnsupportedSchemaVersionGoesToTheDeadLetterQueueWithoutARetry() {
        consume(InboxListenerIT::recordEffect);

        String id = send("Noted", 2);

        assertThat(new String(deadLetter().getBody(), java.nio.charset.StandardCharsets.UTF_8)).contains(id);
        assertThat(calls).hasValue(0);
        assertThat(metrics.rejectedCount()).isEqualTo(1);
        assertThat(metrics.consumeFailureCount()).isZero();
        assertThat(Stack.count("processed_events")).isZero();
    }

    @Test
    void anEnvelopeFromAnotherProducerThanItsRoutingKeyIsRejected() {
        consume(InboxListenerIT::recordEffect);
        String id = UUID.randomUUID().toString();

        // Published under this producer's prefix, but the envelope claims to come from "issue".
        Stack.publish(source + ".Noted", Stack.envelope(id, "issue", "Noted", 1, "{\"note\":\"forged\"}"));

        assertThat(new String(deadLetter().getBody(), java.nio.charset.StandardCharsets.UTF_8)).contains(id);
        assertThat(calls).hasValue(0);
        assertThat(metrics.rejectedCount()).isEqualTo(1);
        assertThat(Stack.count("effects")).isZero();
    }

    @Test
    void aBodyThatIsNotAnEnvelopeIsRejected() {
        consume(InboxListenerIT::recordEffect);

        Stack.publish(source + ".Noted", "not an envelope");

        assertThat(deadLetter()).isNotNull();
        assertThat(calls).hasValue(0);
        assertThat(metrics.rejectedCount()).isEqualTo(1);
    }

    @Test
    void anEventTypeWithoutAHandlerIsRejected() {
        consume(InboxListenerIT::recordEffect);

        String id = send("Unknown", 1);

        assertThat(new String(deadLetter().getBody(), java.nio.charset.StandardCharsets.UTF_8)).contains(id);
        assertThat(calls).hasValue(0);
        assertThat(Stack.count("processed_events")).isZero();
    }

    @Test
    void anEventTheHandlerRefusesGoesToTheDeadLetterQueueAndLeavesNothingBehind() {
        consume(event -> {
            recordEffect(event);
            throw new RejectEventException("data does not match the schema");
        });

        send("Noted", 1);

        assertThat(deadLetter()).isNotNull();
        assertThat(calls).hasValue(1);
        assertThat(metrics.rejectedCount()).isEqualTo(1);
        assertThat(Stack.count("effects")).isZero();
        assertThat(Stack.count("processed_events")).isZero();
    }

    @Test
    void processedIdsOlderThanTheRetentionAreDeleted() {
        Instant now = Instant.parse("2026-11-24T12:00:00Z");
        insertProcessed("old", now.minus(Duration.ofDays(8)));
        insertProcessed("recent", now.minus(Duration.ofDays(6)));
        ProcessedEventPurger purger = new ProcessedEventPurger(new ProcessedEventStore(Stack.jdbc()),
                Clock.fixed(now, ZoneOffset.UTC), Duration.ofDays(7), Duration.ofHours(1));

        assertThat(purger.purge()).isEqualTo(1);

        assertThat(Stack.jdbc().sql("SELECT event_id FROM processed_events").query(String.class).list())
                .containsExactly("recent");
    }

    private static void insertProcessed(String id, Instant at) {
        Stack.jdbc().sql("INSERT INTO processed_events (event_id, event_type, source, processed_at)"
                        + " VALUES (:id, 'Noted', 'test', :at)")
                .param("id", id)
                .param("at", LocalDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
    }
}
