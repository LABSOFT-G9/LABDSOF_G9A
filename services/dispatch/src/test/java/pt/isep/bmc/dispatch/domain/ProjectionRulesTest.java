package pt.isep.bmc.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.domain.DomainFixtures.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.domain.projection.AdministratorAccess;
import pt.isep.bmc.dispatch.domain.projection.MembershipId;
import pt.isep.bmc.dispatch.domain.projection.ProjectedMembership;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/** Technical design 4.3: projections apply by version; only an active administrator membership counts. */
class ProjectionRulesTest {

    private static final MembershipId MEMBERSHIP = new MembershipId(UUID.randomUUID());

    private static ProjectedMembership membership(String role, boolean active, long version) {
        return new ProjectedMembership(MEMBERSHIP, ADMIN_A, BUILDING_A, role, active, version, NOW);
    }

    @Test
    void onlyANewerVersionReplacesTheStoredRow() {
        assertThat(membership("administrator", false, 2).replaces(membership("administrator", true, 1))).isTrue();
        assertThat(membership("administrator", true, 1).replaces(membership("administrator", false, 2)))
                .as("a replayed grant cannot undo a later revocation").isFalse();
        assertThat(membership("administrator", true, 1).replaces(membership("administrator", true, 1))).isFalse();
        assertThat(issue("in_coordination", true, "plumbing").replaces(dispatchablePlumbing())).isFalse();
    }

    @Test
    void residentsRevokedAdministratorsAndOtherUsersAdministerNothing() {
        assertThat(AdministratorAccess.of(ADMIN_A, List.of(membership("resident", true, 1))).administers(BUILDING_A))
                .isFalse();
        assertThat(AdministratorAccess.of(ADMIN_A, List.of(membership("administrator", false, 2)))
                .administers(BUILDING_A)).isFalse();
        assertThat(AdministratorAccess.of(new UserId("someone-else"), List.of(membership("administrator", true, 1)))
                .administers(BUILDING_A)).isFalse();
        assertThat(AdministratorAccess.of(ADMIN_A, List.of(membership("administrator", true, 1)))
                .administers(BUILDING_A)).isTrue();
    }
}
