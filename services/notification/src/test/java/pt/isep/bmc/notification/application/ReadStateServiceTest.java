package pt.isep.bmc.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.notification.application.command.ReadStateService;
import pt.isep.bmc.notification.application.error.NotFoundException;
import pt.isep.bmc.notification.application.query.NotificationQueryService;
import pt.isep.bmc.notification.domain.notification.Notification;
import pt.isep.bmc.notification.domain.notification.NotificationRequest;
import pt.isep.bmc.notification.domain.shared.UserId;

class ReadStateServiceTest {

    static final String ISSUE = "0199f2a1-0000-7000-8000-000000002001";
    static final Instant FIRST = Instant.parse("2026-11-28T10:00:00Z");

    final InMemoryPorts ports = new InMemoryPorts();
    final InMemoryPorts.MutableClock clock = new InMemoryPorts.MutableClock(FIRST);
    final ReadStateService readState = new ReadStateService(ports, ports, clock);
    final NotificationQueryService query = new NotificationQueryService(ports, ports);

    @Test
    void readingOneNotificationKeepsTheFirstTimeAndLowersTheUnreadCount() {
        UUID id = notify("resident-1");
        notify("resident-1");
        assertThat(query.countUnread(new UserId("resident-1"))).isEqualTo(2);

        readState.markRead(new UserId("resident-1"), id);
        clock.set(FIRST.plusSeconds(60));
        readState.markRead(new UserId("resident-1"), id);

        assertThat(query.countUnread(new UserId("resident-1"))).isEqualTo(1);
        assertThat(find("resident-1", id).readAt()).isEqualTo(FIRST);
    }

    @Test
    void anotherUsersNotificationAnswersLikeAnUnknownOneAndStaysUnread() {
        UUID theirs = notify("resident-2");
        assertThatThrownBy(() -> readState.markRead(new UserId("resident-1"), theirs))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> readState.markRead(new UserId("resident-1"), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        assertThat(find("resident-2", theirs).isRead()).isFalse();
    }

    @Test
    void markingAllReadOnlyTouchesTheCallersInbox() {
        notify("resident-1");
        notify("resident-1");
        notify("resident-2");
        assertThat(readState.markAllRead(new UserId("resident-1"))).isEqualTo(2);
        assertThat(readState.markAllRead(new UserId("resident-1"))).isZero();
        assertThat(query.countUnread(new UserId("resident-1"))).isZero();
        assertThat(query.countUnread(new UserId("resident-2"))).isEqualTo(1);
    }

    private UUID notify(String recipient) {
        ports.facade(Clock.fixed(FIRST, ZoneOffset.UTC)).receive(NotificationRequest.of(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), List.of(recipient), "issue.resolved", Map.of("issueId", ISSUE), null));
        return query.list(new UserId(recipient), Optional.empty(), 1).items().getFirst().id();
    }

    private Notification find(String recipient, UUID id) {
        return ports.of(recipient).stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }
}
