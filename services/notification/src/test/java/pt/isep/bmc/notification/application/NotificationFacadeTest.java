package pt.isep.bmc.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.notification.application.command.NotificationFacade;
import pt.isep.bmc.notification.application.command.Receipt;
import pt.isep.bmc.notification.application.error.StorageUnavailableException;
import pt.isep.bmc.notification.application.port.out.DeliveryResult;
import pt.isep.bmc.notification.application.port.out.NotificationChannel;
import pt.isep.bmc.notification.application.port.out.TransactionConflictException;
import pt.isep.bmc.notification.domain.notification.ChannelId;
import pt.isep.bmc.notification.domain.notification.Notification;
import pt.isep.bmc.notification.domain.notification.NotificationRequest;

class NotificationFacadeTest {

    static final String ISSUE = "0199f2a1-0000-7000-8000-000000002001";
    static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    final InMemoryPorts ports = new InMemoryPorts();
    final NotificationFacade facade = ports.facade(CLOCK);

    @Test
    void eachRecipientGetsOneInAppNotificationAndOneSentDelivery() {
        Receipt receipt = facade.receive(request(UUID.randomUUID(), "resident-1", "resident-2"));
        assertThat(receipt).isEqualTo(new Receipt(2, 0));
        assertThat(ports.of("resident-1")).singleElement().satisfies(n -> {
            assertThat(n.templateKey()).isEqualTo("issue.triaged");
            assertThat(n.params()).containsEntry("issueId", ISSUE);
            assertThat(n.createdAt()).isEqualTo(NOW);
        });
        assertThat(ports.of("resident-2")).hasSize(1);
        assertThat(ports.deliveries).hasSize(2).containsValues("sent");
    }

    @Test
    void aRepeatedRequestDeliversNothingAgain() {
        UUID id = UUID.randomUUID();
        facade.receive(request(id, "resident-1"));
        Receipt again = facade.receive(request(id, "resident-1"));
        assertThat(again.isDuplicate()).isTrue();
        assertThat(ports.of("resident-1")).hasSize(1);
    }

    @Test
    void aRepeatedRequestWithANewRecipientOnlyDeliversToTheNewOne() {
        UUID id = UUID.randomUUID();
        facade.receive(request(id, "resident-1"));
        assertThat(facade.receive(request(id, "resident-1", "resident-2"))).isEqualTo(new Receipt(1, 1));
    }

    @Test
    void distinctRequestsForTheSameRecipientStaySeparate() {
        facade.receive(request(UUID.randomUUID(), "resident-1"));
        facade.receive(request(UUID.randomUUID(), "resident-1"));
        assertThat(ports.of("resident-1")).hasSize(2);
    }

    @Test
    void everyRegisteredChannelGetsTheNotificationWithItsOwnDeliveryRow() {
        RecordingChannel queued = new RecordingChannel(new ChannelId("test_queue"), DeliveryResult.PENDING);
        NotificationFacade twoChannels = ports.facade(CLOCK, ports.inApp, queued);
        UUID id = UUID.randomUUID();

        assertThat(twoChannels.receive(request(id, "resident-1"))).isEqualTo(new Receipt(2, 0));
        assertThat(ports.of("resident-1")).hasSize(1);
        assertThat(queued.received).singleElement()
                .satisfies(n -> assertThat(n.recipient().value()).isEqualTo("resident-1"));
        assertThat(ports.deliveries).containsEntry(id + "/resident-1/in_app", "sent")
                .containsEntry(id + "/resident-1/test_queue", "pending");

        assertThat(twoChannels.receive(request(id, "resident-1"))).isEqualTo(new Receipt(0, 2));
        assertThat(queued.received).hasSize(1);
    }

    @Test
    void aChannelAddedLaterDeliversAnOldRequestOnlyOnItself() {
        UUID id = UUID.randomUUID();
        facade.receive(request(id, "resident-1"));
        RecordingChannel added = new RecordingChannel(new ChannelId("test_queue"), DeliveryResult.SENT);
        assertThat(ports.facade(CLOCK, ports.inApp, added).receive(request(id, "resident-1")))
                .isEqualTo(new Receipt(1, 1));
        assertThat(ports.of("resident-1")).hasSize(1);
    }

    @Test
    void channelsMustBeGivenAndDistinct() {
        assertThatThrownBy(() -> new NotificationFacade(ports, ports, List.of(), ports, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ports.facade(CLOCK, ports.inApp, ports.inApp))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aConcurrentTransactionThatGetsInTheWayIsRetried() {
        ports.conflictingTransactions = 3;
        assertThat(facade.receive(request(UUID.randomUUID(), "resident-1"))).isEqualTo(new Receipt(1, 0));
        assertThat(ports.transactionsStarted).isEqualTo(4);
    }

    @Test
    void conflictsBeyondTheLimitAreGivenBackToTheCaller() {
        ports.conflictingTransactions = 100;
        assertThatThrownBy(() -> facade.receive(request(UUID.randomUUID(), "resident-1")))
                .isInstanceOf(TransactionConflictException.class);
        assertThat(ports.transactionsStarted).isEqualTo(10);
    }

    @Test
    void aFailedDeliveryLeavesNoRecipientWithAHalfWrittenRequest() {
        ports.inApp.failDelivery = 2;
        assertThatThrownBy(() -> facade.receive(request(UUID.randomUUID(), "resident-1", "resident-2")))
                .isInstanceOf(StorageUnavailableException.class);
        assertThat(ports.inbox).isEmpty();
        assertThat(ports.deliveries).isEmpty();
    }

    static NotificationRequest request(UUID id, String... recipients) {
        return NotificationRequest.of(id.toString(), UUID.randomUUID().toString(), List.of(recipients),
                "issue.triaged", Map.of("issueId", ISSUE, "category", "plumbing"), "normal");
    }

    /** A second channel, as email would be: records what it was given. */
    static final class RecordingChannel implements NotificationChannel {

        final List<Notification> received = new ArrayList<>();
        private final ChannelId id;
        private final DeliveryResult result;

        RecordingChannel(ChannelId id, DeliveryResult result) {
            this.id = id;
            this.result = result;
        }

        @Override
        public ChannelId id() {
            return id;
        }

        @Override
        public DeliveryResult deliver(Notification notification) {
            received.add(notification);
            return result;
        }
    }
}
