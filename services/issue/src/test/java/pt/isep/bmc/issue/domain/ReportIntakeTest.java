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
import pt.isep.bmc.issue.domain.issue.CriticalAlertsV1;
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
                new ReportId(UUID.randomUUID()), new IssueId(UUID.randomUUID()), NOW, new CriticalAlertsV1());
    }

    @Test
    void createsReceivedIssueReportHistoryAndBothEvents() {
        ReportSubmission s = submit(resident, A, GARAGE_A, "  " + TEXT + "  ");

        assertThat(s.issue().state()).isEqualTo(IssueState.RECEIVED);
        assertThat(s.issue().version()).isEqualTo(1);
        assertThat(s.issue().criticalCheckStatus()).isEqualTo(CriticalCheckStatus.COMPLETED);
        assertThat(s.issue().criticalRulesVersion()).contains("critical-alerts-v1");
        assertThat(s.critical().signals()).isEmpty();
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
    void aReportThatMatchesCriticalRulesGetsItsSignalsAndTheirHistoryEntries() {
        ReportSubmission s = submit(resident, A, GARAGE_A, "There is smoke in the garage and a smell of gas.");

        assertThat(s.issue().criticalCheckStatus()).isEqualTo(CriticalCheckStatus.COMPLETED);
        assertThat(s.issue().version()).isEqualTo(1);
        assertThat(s.critical().signals()).extracting(signal -> signal.code())
                .containsExactly("smoke_or_fire", "gas_smell");
        // After the public "report received" entry, one internal entry per signal.
        assertThat(s.history().sequence()).isEqualTo(1);
        assertThat(s.critical().history()).extracting(entry -> entry.sequence()).containsExactly(2, 3);
        assertThat(s.critical().history()).allSatisfy(
                entry -> assertThat(entry.visibility()).isEqualTo(HistoryVisibility.INTERNAL));
        // The alert changes nothing else: the same two events, and triage is still requested.
        assertThat(s.events()).hasSize(2);
    }

    @Test
    void aRuleSetThatFailsNeverRefusesTheReport() {
        ReportSubmission s = ReportIntake.submit(resident, A, GARAGE_A.id(), Optional.of(GARAGE_A), TEXT,
                new ReportId(UUID.randomUUID()), new IssueId(UUID.randomUUID()), NOW, CriticalCheckTest.failing());

        assertThat(s.issue().state()).isEqualTo(IssueState.RECEIVED);
        assertThat(s.issue().criticalCheckStatus()).isEqualTo(CriticalCheckStatus.PENDING);
        assertThat(s.issue().criticalRulesVersion()).isEmpty();
        assertThat(s.critical().signals()).isEmpty();
        assertThat(s.critical().failure()).contains("java.lang.IllegalArgumentException");
        assertThat(s.report().text().value()).isEqualTo(TEXT);
        assertThat(s.events()).hasSize(2);
    }

    @Test
    void aRefusedReportIsNeverReadByTheCriticalRules() {
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        var counting = CriticalCheckTest.rules("v", text -> {
            runs.incrementAndGet();
            return java.util.List.of();
        });

        assertThatThrownBy(() -> ReportIntake.submit(resident, B, GARAGE_B.id(), Optional.of(GARAGE_B), TEXT,
                new ReportId(UUID.randomUUID()), new IssueId(UUID.randomUUID()), NOW, counting))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> ReportIntake.submit(resident, A, GARAGE_B.id(), Optional.of(GARAGE_B), TEXT,
                new ReportId(UUID.randomUUID()), new IssueId(UUID.randomUUID()), NOW, counting))
                .isInstanceOf(InvalidAreaException.class);
        assertThat(runs).hasValue(0);
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
                new ReportId(UUID.randomUUID()), new IssueId(UUID.randomUUID()), NOW, new CriticalAlertsV1()))
                .isInstanceOf(InvalidAreaException.class);
    }

    @Test
    void invalidTextIsReportedBeforeTheArea() {
        assertThatThrownBy(() -> submit(resident, A, GARAGE_B, "short")).isInstanceOf(ValidationException.class);
    }
}
