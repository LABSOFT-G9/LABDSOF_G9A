package pt.isep.bmc.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.membership.BuildingAccess;
import pt.isep.bmc.issue.domain.membership.Membership;
import pt.isep.bmc.issue.domain.membership.MembershipId;
import pt.isep.bmc.issue.domain.membership.MembershipRole;
import pt.isep.bmc.issue.domain.membership.MembershipStatus;
import pt.isep.bmc.issue.domain.membership.UserId;
import pt.isep.bmc.issue.domain.report.ReportSource;

class BuildingAccessTest {

    static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");
    static final BuildingId A = new BuildingId(UUID.randomUUID());
    static final BuildingId B = new BuildingId(UUID.randomUUID());
    static final UserId ME = new UserId("me");
    static final UserId OTHER = new UserId("other");

    static Membership membership(UserId user, BuildingId building, MembershipRole role, MembershipStatus status,
            Instant from, Instant until) {
        return new Membership(new MembershipId(UUID.randomUUID()), user, building, role, status, from, until, 1);
    }

    static Membership active(UserId user, BuildingId building, MembershipRole role) {
        return membership(user, building, role, MembershipStatus.ACTIVE, NOW.minusSeconds(60), null);
    }

    @Test
    void residentReportsAsResidentOnlyInOwnBuilding() {
        BuildingAccess access = BuildingAccess.of(ME, List.of(active(ME, A, MembershipRole.RESIDENT)), NOW);
        assertThat(access.reportSourceFor(A)).contains(ReportSource.RESIDENT);
        assertThat(access.reportSourceFor(B)).isEmpty();
        assertThat(access.isMemberOf(A)).isTrue();
        assertThat(access.administers(A)).isFalse();
    }

    @Test
    void administratorReportsOnBehalfAndResidentRoleWins() {
        BuildingAccess admin = BuildingAccess.of(ME, List.of(active(ME, A, MembershipRole.ADMINISTRATOR)), NOW);
        assertThat(admin.reportSourceFor(A)).contains(ReportSource.ADMINISTRATOR_ON_BEHALF);

        BuildingAccess both = BuildingAccess.of(ME,
                List.of(active(ME, A, MembershipRole.ADMINISTRATOR), active(ME, A, MembershipRole.RESIDENT)), NOW);
        assertThat(both.reportSourceFor(A)).contains(ReportSource.RESIDENT);
    }

    @Test
    void sameBuildingResidentCannotReadAnotherResidentsReport() {
        BuildingAccess access = BuildingAccess.of(ME, List.of(active(ME, A, MembershipRole.RESIDENT)), NOW);
        assertThat(access.canReadReport(ME, A)).isTrue();
        assertThat(access.canReadReport(OTHER, A)).isFalse();
    }

    @Test
    void administratorReadsReportsOfManagedBuildingsOnly() {
        BuildingAccess access = BuildingAccess.of(ME, List.of(active(ME, A, MembershipRole.ADMINISTRATOR)), NOW);
        assertThat(access.canReadReport(OTHER, A)).isTrue();
        assertThat(access.canReadReport(OTHER, B)).isFalse();
        assertThat(access.administeredBuildings()).containsExactly(A);
    }

    @Test
    void revokedExpiredFutureOrForeignMembershipsGrantNothing() {
        BuildingAccess access = BuildingAccess.of(ME, List.of(
                membership(ME, A, MembershipRole.ADMINISTRATOR, MembershipStatus.REVOKED, NOW.minusSeconds(60), null),
                membership(ME, A, MembershipRole.RESIDENT, MembershipStatus.ACTIVE, NOW.minusSeconds(60), NOW),
                membership(ME, B, MembershipRole.RESIDENT, MembershipStatus.ACTIVE, NOW.plusSeconds(1), null),
                active(OTHER, B, MembershipRole.ADMINISTRATOR)), NOW);
        assertThat(access.isMemberOf(A)).isFalse();
        assertThat(access.isMemberOf(B)).isFalse();
        assertThat(access.reportSourceFor(A)).isEmpty();
        assertThat(access.canReadReport(OTHER, B)).isFalse();
    }

    @Test
    void authorKeepsReadingOwnReportWithoutMembership() {
        assertThat(BuildingAccess.of(ME, List.of(), NOW).canReadReport(ME, A)).isTrue();
    }
}
