package pt.isep.bmc.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.AreaKind;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.event.ReportSubmitted;
import pt.isep.bmc.issue.domain.event.TriageRequested;
import pt.isep.bmc.issue.domain.issue.CriticalCheckStatus;
import pt.isep.bmc.issue.domain.issue.HistoryVisibility;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.membership.BuildingAccess;
import pt.isep.bmc.issue.domain.membership.MembershipRole;
import pt.isep.bmc.issue.domain.report.ReportId;
import pt.isep.bmc.issue.domain.report.ReportIntake;
import pt.isep.bmc.issue.domain.report.ReportSource;
import pt.isep.bmc.issue.domain.report.ReportSubmission;
import pt.isep.bmc.issue.domain.shared.AccessDeniedException;
import pt.isep.bmc.issue.domain.shared.InvalidAreaException;
import pt.isep.bmc.issue.domain.shared.ValidationException;

class ReportIntakeTest {

    static final Instant NOW = BuildingAccessTest.NOW;
    static final BuildingId A = BuildingAccessTest.A;
    static final BuildingId B = BuildingAccessTest.B;
    static final Area GARAGE_A = new Area(new AreaId(UUID.randomUUID()), A, "Garage", AreaKind.COMMON_AREA);
    static final Area GARAGE_B = new Area(new AreaId(UUID.randomUUID()), B, "Garage", AreaKind.COMMON_AREA);
    static final String TEXT = "Water dripping from the ceiling light in the garage.";

    final BuildingAccess resident = BuildingAccess.of(BuildingAccessTest.ME,
            List.of(BuildingAccessTest.active(BuildingAccessTest.ME, A, MembershipRole.RESIDENT)), NOW);

    ReportSubmission submit(BuildingAccess access, BuildingId building, Area area, String text) {
        return ReportIntake.submit(access, building, area.id(), Optional.of(area), text,
                new ReportId(UUID.randomUUID()), new IssueId(UUID.randomUUID()), NOW);
    }

    @Test
    void createsReceivedIssueReportHistoryAndBothEvents() {
        ReportSubmission s = submit(resident, A, GARAGE_A, "  " + TEXT + "  ");

        assertThat(s.issue().state()).isEqualTo(IssueState.RECEIVED);
        assertThat(s.issue().version()).isEqualTo(1);
        assertThat(s.issue().criticalCheckStatus()).isEqualTo(CriticalCheckStatus.PENDING);
        assertThat(s.report().text().value()).isEqualTo(TEXT);
        assertThat(s.report().source()).isEqualTo(ReportSource.RESIDENT);
        assertThat(s.report().authorUserId()).isEqualTo(BuildingAccessTest.ME);
        assertThat(s.history().visibility()).isEqualTo(HistoryVisibility.PUBLIC);
        assertThat(s.history().actorRole()).isEqualTo("resident");
        assertThat(s.events()).hasSize(2);
        assertThat(s.events().get(0)).isInstanceOfSatisfying(ReportSubmitted.class, e -> {
            assertThat(e.reportId()).isEqualTo(s.report().id());
            assertThat(e.aggregateId()).isEqualTo(s.issue().id().value());
            assertThat(e.aggregateVersion()).isEqualTo(1);
        });
        assertThat(s.events().get(1)).isInstanceOfSatisfying(TriageRequested.class, e -> {
            assertThat(e.areaKind()).isEqualTo(AreaKind.COMMON_AREA);
            assertThat(e.text().value()).isEqualTo(TEXT);
            assertThat(e.toString()).doesNotContain("Water");
        });
    }

    @Test
    void nonMemberIsDeniedBeforeAnyOtherCheck() {
        assertThatThrownBy(() -> submit(resident, B, GARAGE_B, ""))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void areaOfAnotherBuildingOrUnknownAreaIsInvalid() {
        assertThatThrownBy(() -> submit(resident, A, GARAGE_B, TEXT)).isInstanceOf(InvalidAreaException.class);
        assertThatThrownBy(() -> ReportIntake.submit(resident, A, GARAGE_A.id(), Optional.empty(), TEXT,
                new ReportId(UUID.randomUUID()), new IssueId(UUID.randomUUID()), NOW))
                .isInstanceOf(InvalidAreaException.class);
    }

    @Test
    void invalidTextIsReportedBeforeTheArea() {
        assertThatThrownBy(() -> submit(resident, A, GARAGE_B, "short")).isInstanceOf(ValidationException.class);
    }
}
