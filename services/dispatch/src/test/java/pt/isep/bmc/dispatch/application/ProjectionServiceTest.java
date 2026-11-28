package pt.isep.bmc.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.dispatch.application.ApplicationFixtures.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.application.command.ProjectionService;
import pt.isep.bmc.dispatch.application.port.out.TransactionConflictException;
import pt.isep.bmc.dispatch.domain.projection.AdministratorAccess;
import pt.isep.bmc.dispatch.domain.projection.MembershipId;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/** Technical design 4.3: Issue's events are applied to the projections by version. */
class ProjectionServiceTest {

    InMemoryPorts ports;
    ProjectionService service;
    IssueId issueId;

    @BeforeEach
    void setUp() {
        ports = new InMemoryPorts();
        service = new ProjectionService(ports, ports, ports);
        issueId = newIssueId();
    }

    @Test
    void theFirstVersionOfAnIssueIsInserted() {
        assertThat(service.apply(triagedPlumbing(issueId))).isTrue();

        assertThat(ports.issues.get(issueId)).isEqualTo(triagedPlumbing(issueId));
    }

    @Test
    void aNewerIssueVersionReplacesTheRow() {
        service.apply(issue(issueId, "in_triage", 2, false));

        assertThat(service.apply(triagedPlumbing(issueId))).isTrue();

        assertThat(ports.issues.get(issueId).version()).isEqualTo(3);
        assertThat(ports.issues.get(issueId).dispatchable()).isTrue();
    }

    @Test
    void theSameOrAnOlderIssueVersionChangesNothing() {
        ProjectedIssue v3 = triagedPlumbing(issueId);
        service.apply(v3);

        assertThat(service.apply(issue(issueId, "cancelled", 3, false))).as("same version").isFalse();
        assertThat(service.apply(issue(issueId, "in_triage", 2, false))).as("older version").isFalse();

        assertThat(ports.issues.get(issueId)).isEqualTo(v3);
    }

    @Test
    void membershipsAreAppliedByVersionAndARevocationEndsTheAccess() {
        MembershipId id = membershipId(4);
        assertThat(service.apply(membership(id, ADMIN_A, BUILDING_A, "administrator", true, 1))).isTrue();
        assertThat(AdministratorAccess.of(ADMIN_A, ports.findByUser(ADMIN_A)).administers(BUILDING_A)).isTrue();

        assertThat(service.apply(membership(id, ADMIN_A, BUILDING_A, "administrator", false, 2))).isTrue();
        assertThat(service.apply(membership(id, ADMIN_A, BUILDING_A, "administrator", true, 1)))
                .as("a late grant does not undo the revocation").isFalse();
        assertThat(service.apply(membership(id, ADMIN_A, BUILDING_A, "administrator", false, 2)))
                .as("a redelivered revocation").isFalse();

        assertThat(ports.memberships.get(id).version()).isEqualTo(2);
        assertThat(AdministratorAccess.of(ADMIN_A, ports.findByUser(ADMIN_A)).administers(BUILDING_A)).isFalse();
    }

    @Test
    void aFirstEventThatLosesTheInsertRaceIsAppliedAgainstTheWinnersRow() {
        // A concurrent first event inserts version 2 just before this one inserts version 3.
        ports.beforeNextInsert = () -> ports.issues.put(issueId, issue(issueId, "in_triage", 2, false));

        assertThat(service.apply(triagedPlumbing(issueId))).isTrue();

        assertThat(ports.issues.get(issueId).version()).isEqualTo(3);
        assertThat(ports.transactionsStarted).isEqualTo(2);
    }

    @Test
    void anOlderFirstEventThatLosesTheInsertRaceChangesNothing() {
        ports.beforeNextInsert = () -> ports.issues.put(issueId, triagedPlumbing(issueId));

        assertThat(service.apply(issue(issueId, "in_triage", 2, false))).isFalse();

        assertThat(ports.issues.get(issueId).version()).isEqualTo(3);
    }

    @Test
    void aDeadlockVictimIsRetriedUntilTheLastAttempt() {
        ports.conflictingTransactions = 9;

        assertThat(service.apply(triagedPlumbing(issueId))).isTrue();
        assertThat(ports.transactionsStarted).isEqualTo(10);

        IssueId other = newIssueId();
        ports.conflictingTransactions = 10;
        assertThatThrownBy(() -> service.apply(triagedPlumbing(other)))
                .isInstanceOf(TransactionConflictException.class);
        assertThat(ports.issues).doesNotContainKey(other);
    }
}
