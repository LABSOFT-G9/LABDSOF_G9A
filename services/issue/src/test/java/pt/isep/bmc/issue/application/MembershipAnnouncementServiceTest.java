package pt.isep.bmc.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.application.command.MembershipAnnouncementService;
import pt.isep.bmc.issue.application.port.out.OutboxEvent;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.event.MembershipGranted;
import pt.isep.bmc.issue.domain.event.MembershipRevoked;
import pt.isep.bmc.issue.domain.membership.Membership;
import pt.isep.bmc.issue.domain.membership.MembershipId;
import pt.isep.bmc.issue.domain.membership.MembershipRole;
import pt.isep.bmc.issue.domain.membership.MembershipStatus;
import pt.isep.bmc.issue.domain.membership.UserId;

class MembershipAnnouncementServiceTest {

    static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    static final BuildingId BUILDING = new BuildingId(UUID.fromString("0199f2a0-0000-7000-8000-00000000b001"));

    final InMemoryPorts ports = new InMemoryPorts();
    final MembershipAnnouncementService service =
            new MembershipAnnouncementService(ports, ports, ports, Clock.fixed(NOW, ZoneOffset.UTC));

    final Membership administrator = membership("d004", "admin-a", MembershipRole.ADMINISTRATOR, MembershipStatus.ACTIVE, 1);
    final Membership revoked = membership("d006", "admin-old", MembershipRole.ADMINISTRATOR, MembershipStatus.REVOKED, 2);

    @BeforeEach
    void memberships() {
        ports.memberships.add(administrator);
        ports.memberships.add(revoked);
    }

    @Test
    void announcesEveryMembershipWithItsCurrentStateAndVersion() {
        assertThat(service.announce()).isEqualTo(2);

        assertThat(ports.outbox).extracting(OutboxEvent::event).containsExactlyInAnyOrder(
                new MembershipGranted(administrator.id(), administrator.userId(), BUILDING, MembershipRole.ADMINISTRATOR, 1, NOW),
                new MembershipRevoked(revoked.id(), revoked.userId(), BUILDING, MembershipRole.ADMINISTRATOR, 2, NOW));
        assertThat(ports.outbox).allSatisfy(event -> {
            assertThat(event.correlationId()).isEqualTo(MembershipAnnouncementService.CORRELATION_ID);
            assertThat(event.event().aggregateId()).isIn(administrator.id().value(), revoked.id().value());
        });
        assertThat(ports.outbox.get(0).event().aggregateVersion()).isEqualTo(1);
        assertThat(ports.outbox.get(1).event().aggregateVersion()).isEqualTo(2);
    }

    @Test
    void runningAgainWritesNothingUntilAVersionChanges() {
        service.announce();
        assertThat(service.announce()).isZero();
        assertThat(ports.outbox).hasSize(2);

        // The administrator is revoked later: a new version, so one new event with a new ID.
        ports.memberships.set(0, new Membership(administrator.id(), administrator.userId(), BUILDING,
                MembershipRole.ADMINISTRATOR, MembershipStatus.REVOKED, administrator.validFrom(), null, 2));
        assertThat(service.announce()).isEqualTo(1);
        assertThat(ports.outbox).hasSize(3).extracting(OutboxEvent::eventId).doesNotHaveDuplicates();
        assertThat(ports.outbox.get(2).event()).isInstanceOf(MembershipRevoked.class);
    }

    @Test
    void aFailureWritesNoneOfTheEvents() {
        ports.memberships.add(membership("d007", "resident-x", MembershipRole.RESIDENT, MembershipStatus.ACTIVE, 1));
        // The first append succeeds, the second fails: the transaction undoes the first.
        ports.failAppendNumber = 2;

        assertThatThrownBy(service::announce).isInstanceOf(IllegalStateException.class);
        assertThat(ports.outbox).isEmpty();
    }

    private static Membership membership(String suffix, String user, MembershipRole role, MembershipStatus status, long version) {
        return new Membership(
                new MembershipId(UUID.fromString("0199f2a0-0000-7000-8000-00000000" + suffix)),
                new UserId(user), BUILDING, role, status, Instant.parse("2026-01-01T00:00:00Z"), null, version);
    }
}
