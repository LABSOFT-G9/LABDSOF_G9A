package pt.isep.bmc.issue.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.application.command.CompletePendingCriticalChecksService;
import pt.isep.bmc.issue.application.port.in.CompletePendingCriticalChecksUseCase.Result;
import pt.isep.bmc.issue.domain.CriticalCheckTest;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.CriticalAlertsV1;
import pt.isep.bmc.issue.domain.issue.CriticalCheckStatus;
import pt.isep.bmc.issue.domain.issue.CriticalFindings;
import pt.isep.bmc.issue.domain.issue.CriticalRules;
import pt.isep.bmc.issue.domain.issue.CriticalSignal;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;
import pt.isep.bmc.issue.domain.membership.UserId;
import pt.isep.bmc.issue.domain.report.Report;
import pt.isep.bmc.issue.domain.report.ReportId;
import pt.isep.bmc.issue.domain.report.ReportSource;
import pt.isep.bmc.issue.domain.report.ReportText;

/**
 * US-14-02 at the application layer, with in-memory ports: critical checks
 * left pending are completed later, once per issue, without touching what an
 * administrator decided and without losing a signal the issue already has.
 */
class CompletePendingCriticalChecksServiceTest {

    static final Instant CREATED = Instant.parse("2026-11-26T09:00:00Z");
    static final Instant NOW = Instant.parse("2026-11-26T10:00:00.123456Z");
    static final Instant NOW_MILLIS = Instant.parse("2026-11-26T10:00:00.123Z");
    static final BuildingId A = new BuildingId(UUID.randomUUID());
    static final AreaId GARAGE = new AreaId(UUID.randomUUID());
    static final UserId RESIDENT = new UserId("resident-a");
    static final String DANGER = "There is smoke in the garage and a smell of gas.";
    static final String ORDINARY = "The stair light on floor 2 has been out for a week.";

    InMemoryPorts ports;
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        ports = new InMemoryPorts();
    }

    CompletePendingCriticalChecksService service(CriticalRules rules, int batchSize) {
        return new CompletePendingCriticalChecksService(ports, ports, ports, ports, rules, ports, clock, batchSize);
    }

    Result run() {
        return service(new CriticalAlertsV1(), 100).completePending();
    }

    /** An issue as it was stored before the check existed, or when the rule set failed: pending, one report. */
    IssueId pendingIssue(IssueState state, long version, String text) {
        IssueId id = new IssueId(UUID.randomUUID());
        boolean decided = state != IssueState.RECEIVED && state != IssueState.IN_TRIAGE;
        ports.issues.add(Issue.restore(id, A, GARAGE, state, version,
                decided ? IssueCategory.OTHER : null, decided ? IssueUrgency.LOW : null,
                CriticalCheckStatus.PENDING, CREATED, CREATED));
        ports.history.add(IssueHistoryEntry.reportReceived(id, ReportSource.RESIDENT, CREATED));
        addReport(id, text, CREATED);
        return id;
    }

    void addReport(IssueId issue, String text, Instant at) {
        Report report = Report.restore(new ReportId(UUID.randomUUID()), issue, A, GARAGE, RESIDENT,
                ReportText.of(text), ReportSource.RESIDENT, at);
        ports.reports.put(report.id(), report);
    }

    Issue issue(IssueId id) {
        return ports.find(id).orElseThrow();
    }

    List<String> codes(IssueId id) {
        return ports.criticalSignals.stream().filter(s -> s.issueId().equals(id)).map(CriticalSignal::code).toList();
    }

    @Test
    void aPendingCheckThatFindsDangerIsCompletedWithItsSignalsHistoryAndTheNextVersion() {
        IssueId id = pendingIssue(IssueState.RECEIVED, 1, DANGER);

        Result result = run();

        assertThat(result).isEqualTo(new Result(1, 0, 2));
        assertThat(issue(id).criticalCheckStatus()).isEqualTo(CriticalCheckStatus.COMPLETED);
        assertThat(issue(id).criticalRulesVersion()).contains("critical-alerts-v1");
        assertThat(issue(id).version()).isEqualTo(2);
        assertThat(issue(id).state()).isEqualTo(IssueState.RECEIVED);
        assertThat(codes(id)).containsExactly("smoke_or_fire", "gas_smell");
        assertThat(ports.criticalSignals).allSatisfy(signal -> {
            assertThat(signal.raisedAt()).isEqualTo(NOW_MILLIS);
            assertThat(signal.rulesVersion()).isEqualTo("critical-alerts-v1");
        });
        assertThat(ports.history).extracting(entry -> entry.type().value())
                .containsExactly("report_received", "critical_alert_raised", "critical_alert_raised");
        assertThat(ports.history).extracting(IssueHistoryEntry::sequence).containsExactly(1, 2, 3);
        assertThat(ports.history.subList(1, 3)).allSatisfy(entry -> assertThat(entry.issueVersion()).isEqualTo(2));
        // No event: the state did not change, and nobody outside Issue reads the check.
        assertThat(ports.outbox).isEmpty();
        assertThat(ports.failedCriticalChecks).isEmpty();
    }

    @Test
    void aPendingCheckThatFindsNothingIsCompletedWithoutMovingTheVersion() {
        IssueId id = pendingIssue(IssueState.RECEIVED, 1, ORDINARY);

        assertThat(run()).isEqualTo(new Result(1, 0, 0));

        assertThat(issue(id).criticalCheckStatus()).isEqualTo(CriticalCheckStatus.COMPLETED);
        assertThat(issue(id).version()).isEqualTo(1);
        assertThat(issue(id).updatedAt()).isEqualTo(CREATED);
        assertThat(ports.criticalSignals).isEmpty();
        assertThat(ports.history).hasSize(1);
    }

    @Test
    void anAlertRaisedLateNeverChangesWhatAnAdministratorDecided() {
        IssueId id = pendingIssue(IssueState.TRIAGED, 3, DANGER);

        run();

        assertThat(issue(id).state()).isEqualTo(IssueState.TRIAGED);
        assertThat(issue(id).category()).contains(IssueCategory.OTHER);
        assertThat(issue(id).urgency()).contains(IssueUrgency.LOW);
        assertThat(issue(id).version()).isEqualTo(4);
        assertThat(codes(id)).containsExactly("smoke_or_fire", "gas_smell");
    }

    @Test
    void aRuleSetThatFailsAgainLeavesTheCheckPendingLogsItAndRunsTheOtherIssues() {
        IssueId first = pendingIssue(IssueState.RECEIVED, 1, DANGER);
        IssueId second = pendingIssue(IssueState.RECEIVED, 1, DANGER);
        CriticalRules failsOnce = new CriticalRules() {
            private int runs;

            @Override
            public String version() {
                return "critical-alerts-v1";
            }

            @Override
            public List<String> evaluate(ReportText text) {
                if (runs++ == 0) {
                    throw new IllegalStateException("cannot read: " + text.value());
                }
                return List.of("smoke_or_fire");
            }
        };

        Result result = service(failsOnce, 100).completePending();

        assertThat(result).isEqualTo(new Result(1, 1, 1));
        List<Issue> pending = ports.issues.stream()
                .filter(issue -> issue.criticalCheckStatus() == CriticalCheckStatus.PENDING).toList();
        assertThat(pending).singleElement().satisfies(issue -> {
            assertThat(issue.version()).isEqualTo(1);
            assertThat(issue.criticalRulesVersion()).isEmpty();
            assertThat(codes(issue.id())).isEmpty();
            assertThat(ports.failedCriticalChecks).containsExactly(issue.id() + ": java.lang.IllegalStateException");
        });
        assertThat(ports.failedCriticalChecks.getFirst()).doesNotContain("smoke", "gas");
        assertThat(List.of(first, second)).filteredOn(id -> !codes(id).isEmpty()).hasSize(1);
    }

    @Test
    void aRunEndsEvenWhenEveryCheckFailsAndTriesEachIssueOnce() {
        for (int i = 0; i < 5; i++) {
            pendingIssue(IssueState.RECEIVED, 1, DANGER);
        }

        Result result = service(CriticalCheckTest.failing(), 2).completePending();

        assertThat(result).isEqualTo(new Result(0, 5, 0));
        assertThat(ports.failedCriticalChecks).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    void everyPendingIssueIsReachedAcrossBatches() {
        for (int i = 0; i < 5; i++) {
            pendingIssue(IssueState.RECEIVED, 1, i % 2 == 0 ? DANGER : ORDINARY);
        }

        assertThat(service(new CriticalAlertsV1(), 2).completePending()).isEqualTo(new Result(5, 0, 6));
        assertThat(ports.issues).allSatisfy(
                issue -> assertThat(issue.criticalCheckStatus()).isEqualTo(CriticalCheckStatus.COMPLETED));
    }

    @Test
    void aSecondRunDoesNothing() {
        IssueId id = pendingIssue(IssueState.RECEIVED, 1, DANGER);
        run();

        assertThat(run()).isEqualTo(new Result(0, 0, 0));

        assertThat(issue(id).version()).isEqualTo(2);
        assertThat(codes(id)).hasSize(2);
        assertThat(ports.history).hasSize(3);
    }

    @Test
    void anIssueAlreadyCheckedIsLeftAlone() {
        IssueId id = new IssueId(UUID.randomUUID());
        ports.issues.add(Issue.receive(id, A, GARAGE, CREATED, CriticalFindings.none("critical-alerts-v1")));
        addReport(id, DANGER, CREATED);

        assertThat(run()).isEqualTo(new Result(0, 0, 0));
        assertThat(ports.criticalSignals).isEmpty();
        assertThat(issue(id).version()).isEqualTo(1);
    }

    @Test
    void aSignalTheIssueAlreadyHasIsKeptAndNotRaisedTwice() {
        IssueId id = pendingIssue(IssueState.RECEIVED, 1, DANGER);
        CriticalSignal kept = new CriticalSignal(id, "smoke_or_fire", "critical-alerts-v0", 2, CREATED);
        ports.criticalSignals.add(kept);
        ports.history.add(IssueHistoryEntry.criticalAlertRaised(issue(id), 2, CREATED));

        assertThat(run()).isEqualTo(new Result(1, 0, 1));

        assertThat(ports.criticalSignals).contains(kept);
        assertThat(codes(id)).containsExactly("smoke_or_fire", "gas_smell");
        assertThat(ports.history).extracting(IssueHistoryEntry::sequence).containsExactly(1, 2, 3);
    }

    @Test
    void aCheckThatOnlyFindsWhatTheIssueAlreadyHasMovesNothing() {
        IssueId id = pendingIssue(IssueState.RECEIVED, 1, "There is smoke in the garage.");
        ports.criticalSignals.add(new CriticalSignal(id, "smoke_or_fire", "critical-alerts-v0", 2, CREATED));

        assertThat(run()).isEqualTo(new Result(1, 0, 0));

        assertThat(issue(id).version()).isEqualTo(1);
        assertThat(codes(id)).containsExactly("smoke_or_fire");
    }

    @Test
    void everyReportOfTheIssueIsRead() {
        IssueId id = pendingIssue(IssueState.RECEIVED, 1, ORDINARY);
        addReport(id, "Now there is a smell of gas as well.", CREATED.plusSeconds(60));

        assertThat(run()).isEqualTo(new Result(1, 0, 1));
        assertThat(codes(id)).containsExactly("gas_smell");
    }

    @Test
    void anIssueWithNoReportToReadIsNeverCalledChecked() {
        IssueId id = new IssueId(UUID.randomUUID());
        ports.issues.add(Issue.restore(id, A, GARAGE, IssueState.RECEIVED, 1, null, null,
                CriticalCheckStatus.PENDING, CREATED, CREATED));

        assertThat(run()).isEqualTo(new Result(0, 1, 0));
        assertThat(issue(id).criticalCheckStatus()).isEqualTo(CriticalCheckStatus.PENDING);
        assertThat(ports.failedCriticalChecks).hasSize(1);
    }

    @Test
    void aFailureWhileStoringOneIssueUndoesThatIssueOnlyAndTheOthersAreCompleted() {
        IssueId first = pendingIssue(IssueState.RECEIVED, 1, DANGER);
        IssueId second = pendingIssue(IssueState.RECEIVED, 1, DANGER);
        // The second history entry of the run cannot be stored: the first issue's transaction fails half-way.
        ports.failHistoryNumber = 2;

        assertThat(run()).isEqualTo(new Result(1, 1, 2));

        List<Issue> pending = ports.issues.stream()
                .filter(issue -> issue.criticalCheckStatus() == CriticalCheckStatus.PENDING).toList();
        assertThat(pending).singleElement().satisfies(issue -> {
            // Nothing of the failed issue stays: no signal, no history entry, the version it had.
            assertThat(issue.version()).isEqualTo(1);
            assertThat(codes(issue.id())).isEmpty();
            assertThat(ports.history).filteredOn(entry -> entry.issueId().equals(issue.id())).hasSize(1);
            assertThat(ports.failedCriticalChecks).containsExactly(issue.id() + ": java.lang.IllegalStateException");
        });
        assertThat(List.of(first, second)).filteredOn(id -> codes(id).size() == 2).hasSize(1);
    }
}
