package pt.isep.bmc.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.issue.application.SharingFixtures.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.application.command.SubscriptionService;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.shared.AccessDeniedException;

/** US-04: the caller's own subscription, at the application layer with in-memory ports. */
class SubscriptionServiceTest {

    SharingFixtures world;
    InMemoryPorts ports;
    SubscriptionService service;
    IssueId issueId;

    @BeforeEach
    void setUp() {
        world = new SharingFixtures();
        ports = world.ports;
        issueId = world.issue.id();
        service = new SubscriptionService(ports, ports, ports, ports, ports.subscriptionRepository(), CLOCK);
    }

    @Test
    void anyoneWhoSeesTheIssueTurnsTheirOwnSubscriptionOnAndOffAndRepeatingIsSafe() {
        world.share();

        assertThat(service.subscribe(NEIGHBOUR, issueId)).isTrue();
        assertThat(service.subscribe(NEIGHBOUR, issueId)).as("repeated").isTrue();
        assertThat(service.subscribe(ADMIN, issueId)).isTrue();
        assertThat(ports.subscriptions).containsExactly(
                new InMemoryPorts.Mark(issueId, NEIGHBOUR), new InMemoryPorts.Mark(issueId, ADMIN));

        assertThat(service.unsubscribe(NEIGHBOUR, issueId)).isFalse();
        assertThat(service.unsubscribe(NEIGHBOUR, issueId)).as("repeated").isFalse();
        assertThat(ports.subscriptions).containsExactly(new InMemoryPorts.Mark(issueId, ADMIN));
    }

    @Test
    void beforeASummaryOnlyTheReporterAndTheAdministratorsSeeTheIssue() {
        assertThat(service.subscribe(REPORTER, issueId)).isTrue();
        assertThat(service.subscribe(ADMIN, issueId)).isTrue();
        assertThatThrownBy(() -> service.subscribe(NEIGHBOUR, issueId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.subscribe(OUTSIDER, issueId)).isInstanceOf(AccessDeniedException.class);
        assertThat(ports.subscriptions).hasSize(2);
    }

    @Test
    void aSubscriptionNeverWritesTheIssueAnEventHistoryOrImpact() {
        world.share();
        int events = ports.outbox.size();
        int entries = ports.history.size();

        service.subscribe(NEIGHBOUR, issueId);

        assertThat(world.stored().version()).isEqualTo(2);
        assertThat(ports.outbox).hasSize(events);
        assertThat(ports.history).hasSize(entries);
        assertThat(ports.confirmations).isEmpty();
    }

    @Test
    void someoneWhoLostAccessCanNoLongerChangeTheirSubscription() {
        world.share();
        service.subscribe(NEIGHBOUR, issueId);
        world.revoke(NEIGHBOUR);

        assertThatThrownBy(() -> service.unsubscribe(NEIGHBOUR, issueId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anUnknownIssueIsNotFound() {
        assertThatThrownBy(() -> service.subscribe(REPORTER, new IssueId(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }
}
