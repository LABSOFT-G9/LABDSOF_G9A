package pt.isep.bmc.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import pt.isep.bmc.messaging.MessagingMetrics;
import pt.isep.bmc.messaging.support.Stack;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The outbox publisher against a real MySQL 8.4 and a real RabbitMQ 4.4 (NFR-04, ADR-003 "Verification"). */
class OutboxPublisherIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    private final MessagingMetrics metrics = new MessagingMetrics(null);
    private String source;
    private String queue;

    @BeforeEach
    void setUp() {
        Stack.resetDatabase();
        // A producer name of its own per test, so tests never see each other's messages.
        source = "p" + System.nanoTime();
        queue = Stack.declareQueue("outbox-it");
    }

    private OutboxPublisher publisher(CachingConnectionFactory connectionFactory, OutboxStore store, String... notPublished) {
        RabbitTemplate rabbit = new RabbitTemplate(connectionFactory);
        rabbit.setMandatory(true);
        return new OutboxPublisher(store, rabbit, Stack.transaction(), metrics, Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                Stack.EXCHANGE, 50, Duration.ofSeconds(5), Set.of(notPublished));
    }

    private OutboxPublisher publisher(String... notPublished) {
        return publisher(Stack.connectionFactory(), new OutboxStore(Stack.jdbc()), notPublished);
    }

    private static String idOf(Message message) {
        return JSON.readTree(message.getBody()).get("id").stringValue();
    }

    @Test
    void sendsPendingRowsInOrderAndMarksThemSent() {
        Stack.bind(queue, source + ".#");
        String first = Stack.insertOutboxRow(source, "First", NOW.minusSeconds(30));
        String second = Stack.insertOutboxRow(source, "Second", NOW.minusSeconds(20));
        String third = Stack.insertOutboxRow(source, "Third", NOW.minusSeconds(10));

        OutboxPublisher.Pass pass = publisher().publishPending();

        assertThat(pass).isEqualTo(new OutboxPublisher.Pass(3, true, false));
        Message message = Stack.receive(queue, 5_000);
        assertThat(idOf(message)).isEqualTo(first);
        assertThat(idOf(Stack.receive(queue, 5_000))).isEqualTo(second);
        assertThat(idOf(Stack.receive(queue, 5_000))).isEqualTo(third);

        // The message is the stored CloudEvents envelope, persistent, with the event ID as message ID.
        JsonNode envelope = JSON.readTree(message.getBody());
        assertThat(envelope.get("specversion").stringValue()).isEqualTo("1.0");
        assertThat(envelope.get("type").stringValue()).isEqualTo("First");
        assertThat(envelope.get("source").stringValue()).isEqualTo(source);
        assertThat(envelope.get("data").get("note").stringValue()).isEqualTo("First");
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/cloudevents+json");
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(first);
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo(source + ".First");

        for (String id : new String[] {first, second, third}) {
            assertThat(Stack.outboxStatus(id)).isEqualTo("sent");
            assertThat(Stack.outboxAttempts(id)).isEqualTo(1);
        }
        assertThat(Stack.jdbc().sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single())
                .isZero();
        assertThat(metrics.publishedCount()).isEqualTo(3);
        assertThat(metrics.outboxPending()).isZero();
    }

    @Test
    void aMessageBoundToNoQueueIsReturnedAndItsRowStaysPending() {
        Stack.bind(queue, source + ".Bound");
        String unbound = Stack.insertOutboxRow(source, "Unbound", NOW.minusSeconds(90));
        String bound = Stack.insertOutboxRow(source, "Bound", NOW.minusSeconds(10));

        OutboxPublisher.Pass pass = publisher().publishPending();

        // The broker confirms the unroutable message too; only the return tells. The pass stops there, in order.
        assertThat(pass).isEqualTo(new OutboxPublisher.Pass(0, false, false));
        assertThat(metrics.returnedCount()).isEqualTo(1);
        assertThat(Stack.outboxStatus(unbound)).isEqualTo("pending");
        assertThat(Stack.outboxAttempts(unbound)).isEqualTo(1);
        assertThat(Stack.outboxStatus(bound)).isEqualTo("pending");
        assertThat(Stack.outboxAttempts(bound)).isZero();
        assertThat(Stack.receive(queue, 500)).isNull();
        // Outbox lag: two rows wait, the oldest for 90 s.
        assertThat(metrics.outboxPending()).isEqualTo(2);
        assertThat(metrics.outboxOldestPendingSeconds()).isEqualTo(90);

        // Once the deployment binds the key, the same rows go out.
        Stack.bind(queue, source + ".Unbound");
        assertThat(publisher().publishPending()).isEqualTo(new OutboxPublisher.Pass(2, true, false));
        assertThat(idOf(Stack.receive(queue, 5_000))).isEqualTo(unbound);
        assertThat(idOf(Stack.receive(queue, 5_000))).isEqualTo(bound);
        assertThat(Stack.outboxAttempts(unbound)).isEqualTo(2);
    }

    @Test
    void eventTypesWithNoConsumerStayInTheOutboxAndBlockNothing() {
        Stack.bind(queue, source + ".Consumed");
        String local = Stack.insertOutboxRow(source, "LocalOnly", NOW.minusSeconds(60));
        String consumed = Stack.insertOutboxRow(source, "Consumed", NOW.minusSeconds(10));

        OutboxPublisher.Pass pass = publisher("LocalOnly").publishPending();

        assertThat(pass).isEqualTo(new OutboxPublisher.Pass(1, true, false));
        assertThat(idOf(Stack.receive(queue, 5_000))).isEqualTo(consumed);
        assertThat(Stack.outboxStatus(local)).isEqualTo("pending");
        assertThat(Stack.outboxAttempts(local)).isZero();
        assertThat(metrics.returnedCount()).isZero();
        // They do not count as lag.
        assertThat(metrics.outboxPending()).isZero();
    }

    @Test
    void aRowCommittedWhileTheBrokerIsDownIsSentWhenItIsBack() {
        Stack.bind(queue, source + ".#");
        String id = Stack.insertOutboxRow(source, "Survivor", NOW.minusSeconds(10));
        // Nothing listens on this port: the broker is "down".
        CachingConnectionFactory down = Stack.connectionFactory("localhost", 1);

        OutboxPublisher.Pass failed = publisher(down, new OutboxStore(Stack.jdbc())).publishPending();

        assertThat(failed).isEqualTo(new OutboxPublisher.Pass(0, false, false));
        assertThat(Stack.outboxStatus(id)).isEqualTo("pending");
        assertThat(Stack.outboxAttempts(id)).isEqualTo(1);
        assertThat(metrics.publishFailureCount()).isEqualTo(1);
        down.destroy();

        // A new publisher, as after a restart, finds the row and sends it.
        assertThat(publisher().publishPending()).isEqualTo(new OutboxPublisher.Pass(1, true, false));
        assertThat(idOf(Stack.receive(queue, 5_000))).isEqualTo(id);
        assertThat(Stack.outboxStatus(id)).isEqualTo("sent");
    }

    @Test
    void aRowConfirmedButNotMarkedIsSentAgain() {
        Stack.bind(queue, source + ".#");
        String id = Stack.insertOutboxRow(source, "Twice", NOW.minusSeconds(10));
        // The process "dies" after the broker's confirm and before the row is marked sent.
        AtomicBoolean died = new AtomicBoolean();
        OutboxStore dyingOnce = new OutboxStore(Stack.jdbc()) {
            @Override
            public void markSent(String rowId, Instant now) {
                if (died.compareAndSet(false, true)) {
                    throw new IllegalStateException("killed between confirm and commit");
                }
                super.markSent(rowId, now);
            }
        };
        OutboxPublisher publisher = publisher(Stack.connectionFactory(), dyingOnce);

        assertThatThrownBy(publisher::publishPending).isInstanceOf(IllegalStateException.class);
        assertThat(Stack.outboxStatus(id)).isEqualTo("pending");

        assertThat(publisher.publishPending()).isEqualTo(new OutboxPublisher.Pass(1, true, false));

        // At least once: the event is in the queue twice, with the same ID for the consumer to deduplicate.
        Message first = Stack.receive(queue, 5_000);
        Message second = Stack.receive(queue, 5_000);
        assertThat(idOf(first)).isEqualTo(id);
        assertThat(idOf(second)).isEqualTo(id);
        assertThat(new String(first.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(new String(second.getBody(), StandardCharsets.UTF_8));
        assertThat(Stack.outboxStatus(id)).isEqualTo("sent");
    }

    @Test
    void aFullBatchSaysThatMoreRowsWait() {
        Stack.bind(queue, source + ".#");
        for (int i = 0; i < 3; i++) {
            Stack.insertOutboxRow(source, "Row" + i, NOW.minusSeconds(30 - i));
        }
        RabbitTemplate rabbit = new RabbitTemplate(Stack.connectionFactory());
        rabbit.setMandatory(true);
        OutboxPublisher twoAtATime = new OutboxPublisher(new OutboxStore(Stack.jdbc()), rabbit, Stack.transaction(),
                metrics, Clock.systemUTC(), Stack.EXCHANGE, 2, Duration.ofSeconds(5), Set.of());

        assertThat(twoAtATime.publishPending()).isEqualTo(new OutboxPublisher.Pass(2, true, true));
        assertThat(twoAtATime.publishPending()).isEqualTo(new OutboxPublisher.Pass(1, true, false));
        assertThat(Stack.jdbc().sql("SELECT COUNT(*) FROM outbox WHERE status = 'pending'").query(Long.class).single())
                .isZero();
    }
}
