package pt.isep.bmc.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.notification.application.command.NotificationFacade;
import pt.isep.bmc.notification.application.query.NotificationPage;
import pt.isep.bmc.notification.application.query.NotificationQueryService;
import pt.isep.bmc.notification.domain.notification.Notification;
import pt.isep.bmc.notification.domain.notification.NotificationRequest;
import pt.isep.bmc.notification.domain.shared.UserId;

class NotificationQueryServiceTest {

    static final String ISSUE = "0199f2a1-0000-7000-8000-000000002001";

    final InMemoryPorts ports = new InMemoryPorts();
    final NotificationFacade receive = ports.facade(Clock.systemUTC());
    final NotificationQueryService query = new NotificationQueryService(ports, ports);

    @Test
    void aCallerOnlyEverSeesTheirOwnNotifications() {
        notify("resident-1");
        notify("resident-2");
        NotificationPage page = query.list(new UserId("resident-1"), Optional.empty(), 20);
        assertThat(page.items()).extracting(n -> n.recipient().value()).containsOnly("resident-1");
        assertThat(query.list(new UserId("stranger"), Optional.empty(), 20).items()).isEmpty();
    }

    @Test
    void pagesGoFromNewestToOldestWithoutGapsOrRepeats() {
        for (int i = 0; i < 5; i++) {
            notify("resident-1");
        }
        UserId caller = new UserId("resident-1");
        NotificationPage first = query.list(caller, Optional.empty(), 2);
        NotificationPage second = query.list(caller, first.nextBefore(), 2);
        NotificationPage third = query.list(caller, second.nextBefore(), 2);

        assertThat(first.items()).hasSize(2);
        assertThat(second.items()).hasSize(2);
        assertThat(third.items()).hasSize(1);
        assertThat(third.nextBefore()).isEmpty();
        List<UUID> ids = java.util.stream.Stream.of(first, second, third)
                .flatMap(p -> p.items().stream()).map(Notification::id).toList();
        assertThat(ids).doesNotHaveDuplicates().hasSize(5).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void aCursorFromAnotherInboxRevealsNothingOfIt() {
        notify("resident-2");
        UUID theirs = query.list(new UserId("resident-2"), Optional.empty(), 1).items().getFirst().id();
        notify("resident-1");
        assertThat(query.list(new UserId("resident-1"), Optional.of(theirs), 20).items())
                .allSatisfy(n -> assertThat(n.recipient().value()).isEqualTo("resident-1"));
    }

    private void notify(String recipient) {
        receive.receive(NotificationRequest.of(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                List.of(recipient), "issue.resolved", Map.of("issueId", ISSUE), null));
    }
}
