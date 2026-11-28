package pt.isep.bmc.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.issue.application.SharingFixtures.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.application.command.ImpactService;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.application.query.ImpactView;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.shared.AccessDeniedException;
import pt.isep.bmc.issue.domain.shared.ForbiddenException;
import pt.isep.bmc.issue.domain.shared.NotPublishedException;

/** US-04: confirming and withdrawing impact, at the application layer with in-memory ports. */
class ImpactServiceTest {

    SharingFixtures world;
    InMemoryPorts ports;
    ImpactService service;
    IssueId issueId;

    @BeforeEach
    void setUp() {
        world = new SharingFixtures();
        ports = world.ports;
        issueId = world.issue.id();
        service = new ImpactService(ports, ports, ports, ports, ports.impactRepository(), CLOCK);
    }

    @Test
    void residentsConfirmAndWithdrawTheirOwnImpactAndRepeatingIsSafe() {
        world.share();

        assertThat(service.confirm(NEIGHBOUR, issueId)).isEqualTo(new ImpactView(1, true));
        assertThat(service.confirm(NEIGHBOUR, issueId)).as("repeated").isEqualTo(new ImpactView(1, true));
        assertThat(service.confirm(REPORTER, issueId)).isEqualTo(new ImpactView(2, true));

        assertThat(service.withdraw(NEIGHBOUR, issueId)).isEqualTo(new ImpactView(1, false));
        assertThat(service.withdraw(NEIGHBOUR, issueId)).as("repeated").isEqualTo(new ImpactView(1, false));
        assertThat(ports.confirmations).containsExactly(new InMemoryPorts.Mark(issueId, REPORTER));
    }

    @Test
    void impactNeverWritesTheIssueAnEventOrHistory() {
        world.share();
        int events = ports.outbox.size();
        int entries = ports.history.size();

        service.confirm(NEIGHBOUR, issueId);
        service.withdraw(NEIGHBOUR, issueId);

        assertThat(world.stored().version()).isEqualTo(2);
        assertThat(ports.outbox).hasSize(events);
        assertThat(ports.history).hasSize(entries);
    }

    @Test
    void aConfirmationCountsOnlyWhileItsAuthorIsAnActiveResident() {
        world.share();
        service.confirm(NEIGHBOUR, issueId);
        assertThat(service.confirm(REPORTER, issueId).count()).isEqualTo(2);

        world.revoke(NEIGHBOUR);
        assertThat(service.confirm(REPORTER, issueId).count()).as("the neighbour left the building").isEqualTo(1);
        assertThat(ports.confirmations).as("the confirmation is kept").hasSize(2);

        world.restore(NEIGHBOUR);
        assertThat(service.confirm(REPORTER, issueId).count()).as("back in the building").isEqualTo(2);

        world.endValidity(NEIGHBOUR, NOW.minusSeconds(1));
        assertThat(service.confirm(REPORTER, issueId).count()).as("the membership has ended").isEqualTo(1);
    }

    @Test
    void theCheckOrderIsScopeThenResidentThenPublished() {
        // Before a summary exists: the neighbour does not see the issue; the reporter does, but it is not shared.
        assertThatThrownBy(() -> service.confirm(NEIGHBOUR, issueId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.confirm(REPORTER, issueId)).isInstanceOf(NotPublishedException.class);
        // An administrator is refused for the role before the missing summary is mentioned.
        assertThatThrownBy(() -> service.confirm(ADMIN, issueId)).isInstanceOf(ForbiddenException.class);

        world.share();
        assertThatThrownBy(() -> service.confirm(ADMIN, issueId)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.withdraw(ADMIN, issueId)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.confirm(OUTSIDER, issueId)).isInstanceOf(AccessDeniedException.class);
        assertThat(ports.confirmations).isEmpty();
    }

    @Test
    void aReporterWhoLeftTheBuildingMayNotChangeTheirImpact() {
        world.share();
        service.confirm(REPORTER, issueId);
        world.revoke(REPORTER);

        // Still visible to its reporter, but only residents change their impact.
        assertThatThrownBy(() -> service.withdraw(REPORTER, issueId)).isInstanceOf(ForbiddenException.class);
        assertThat(ports.confirmations).hasSize(1);
    }

    @Test
    void anUnknownIssueIsNotFound() {
        assertThatThrownBy(() -> service.confirm(REPORTER, new IssueId(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }
}
