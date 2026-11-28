package pt.isep.bmc.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.application.command.PurgeExpiredIdempotencyKeysService;
import pt.isep.bmc.issue.application.command.ReportReceipt;
import pt.isep.bmc.issue.application.command.SubmitReportCommand;
import pt.isep.bmc.issue.application.command.SubmitReportService;
import pt.isep.bmc.issue.application.error.IdempotencyKeyReusedException;
import pt.isep.bmc.issue.application.error.StorageUnavailableException;
import pt.isep.bmc.issue.application.idempotency.IdempotencyKey;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.AreaKind;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.CriticalAlertsV1;
import pt.isep.bmc.issue.domain.issue.CriticalCheckStatus;
import pt.isep.bmc.issue.domain.issue.CriticalRules;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.membership.Membership;
import pt.isep.bmc.issue.domain.membership.MembershipId;
import pt.isep.bmc.issue.domain.membership.MembershipRole;
import pt.isep.bmc.issue.domain.membership.MembershipStatus;
import pt.isep.bmc.issue.domain.membership.UserId;
import pt.isep.bmc.issue.domain.shared.AccessDeniedException;
import pt.isep.bmc.issue.domain.shared.InvalidAreaException;
import pt.isep.bmc.issue.domain.shared.ValidationException;

class SubmitReportServiceTest {

    static final Instant NOW = Instant.parse("2026-11-23T10:00:00.123456Z");
    static final UserId RESIDENT = new UserId("resident-a");
    static final BuildingId A = new BuildingId(UUID.randomUUID());
    static final BuildingId B = new BuildingId(UUID.randomUUID());
    static final Area GARAGE_A = new Area(new AreaId(UUID.randomUUID()), A, "Garage", AreaKind.COMMON_AREA);
    static final Area GARAGE_B = new Area(new AreaId(UUID.randomUUID()), B, "Garage", AreaKind.COMMON_AREA);
    static final String TEXT = "Water dripping from the ceiling light in the garage.";

    InMemoryPorts ports;
    MutableClock clock;
    SubmitReportService service;

    @BeforeEach
    void setUp() {
        ports = new InMemoryPorts();
        ports.memberships.add(new Membership(new MembershipId(UUID.randomUUID()), RESIDENT, A, MembershipRole.RESIDENT,
                MembershipStatus.ACTIVE, NOW.minusSeconds(3600), null, 1));
        ports.areas.put(GARAGE_A.id(), GARAGE_A);
        ports.areas.put(GARAGE_B.id(), GARAGE_B);
        clock = new MutableClock(NOW);
        service = service(new CriticalAlertsV1());
    }

    SubmitReportService service(CriticalRules rules) {
        return new SubmitReportService(ports, ports, ports, ports, ports, ports, rules, ports, ports, ports, ports, clock);
    }

    SubmitReportCommand command(String key, Area area, String text) {
        return new SubmitReportCommand(RESIDENT, new IdempotencyKey(key), area.buildingId(), area.id(), text, "c-1");
    }

    @Test
    void storesReportIssueHistoryAndBothOutboxEventsAtomically() {
        ReportReceipt receipt = service.submit(command("k1", GARAGE_A, TEXT));

        assertThat(receipt.state()).isEqualTo(IssueState.RECEIVED);
        assertThat(receipt.version()).isEqualTo(1);
        assertThat(receipt.submittedAt()).isEqualTo(Instant.parse("2026-11-23T10:00:00.123Z"));
        assertThat(receipt.replayed()).isFalse();
        assertThat(ports.reports).containsKey(receipt.reportId());
        assertThat(ports.issues).singleElement().satisfies(i -> assertThat(i.id()).isEqualTo(receipt.issueId()));
        assertThat(ports.history).hasSize(1);
        assertThat(ports.outbox).extracting(e -> e.event().type())
                .containsExactly("ReportSubmitted", "TriageRequested");
        assertThat(ports.outbox).allSatisfy(e -> assertThat(e.correlationId()).isEqualTo("c-1"));
        assertThat(ports.keys).hasSize(1);
    }

    static final String DANGER = "There is smoke in the garage and a smell of gas.";

    @Test
    void storesTheCriticalSignalsAndTheirHistoryWithTheReport() {
        ReportReceipt receipt = service.submit(command("k1", GARAGE_A, DANGER));

        assertThat(receipt.version()).isEqualTo(1);
        assertThat(ports.issues).singleElement().satisfies(issue -> {
            assertThat(issue.criticalCheckStatus()).isEqualTo(CriticalCheckStatus.COMPLETED);
            assertThat(issue.criticalRulesVersion()).contains("critical-alerts-v1");
        });
        assertThat(ports.criticalSignals).extracting(signal -> signal.code())
                .containsExactly("smoke_or_fire", "gas_smell");
        assertThat(ports.criticalSignals).allSatisfy(
                signal -> assertThat(signal.issueId()).isEqualTo(receipt.issueId()));
        assertThat(ports.history).extracting(entry -> entry.type().value())
                .containsExactly("report_received", "critical_alert_raised", "critical_alert_raised");
        assertThat(ports.history).extracting(entry -> entry.sequence()).containsExactly(1, 2, 3);
        // Triage is still requested: an alert is not a decision and does not replace the suggestion.
        assertThat(ports.outbox).extracting(e -> e.event().type())
                .containsExactly("ReportSubmitted", "TriageRequested");
        assertThat(ports.failedCriticalChecks).isEmpty();
    }

    @Test
    void anOrdinaryReportCompletesTheCheckWithNoSignal() {
        service.submit(command("k1", GARAGE_A, TEXT));

        assertThat(ports.issues).singleElement().satisfies(
                issue -> assertThat(issue.criticalCheckStatus()).isEqualTo(CriticalCheckStatus.COMPLETED));
        assertThat(ports.criticalSignals).isEmpty();
        assertThat(ports.history).hasSize(1);
    }

    @Test
    void aRuleSetThatFailsNeitherRefusesNorDelaysTheReceiptAndLeavesTheCheckPending() {
        ReportReceipt receipt = service(pt.isep.bmc.issue.domain.CriticalCheckTest.failing())
                .submit(command("k1", GARAGE_A, DANGER));

        assertThat(receipt.state()).isEqualTo(IssueState.RECEIVED);
        assertThat(receipt.replayed()).isFalse();
        assertThat(ports.reports).containsKey(receipt.reportId());
        assertThat(ports.issues).singleElement().satisfies(issue -> {
            assertThat(issue.criticalCheckStatus()).isEqualTo(CriticalCheckStatus.PENDING);
            assertThat(issue.criticalRulesVersion()).isEmpty();
        });
        assertThat(ports.criticalSignals).isEmpty();
        assertThat(ports.history).hasSize(1);
        assertThat(ports.outbox).hasSize(2);
        // Visible to operators, with the issue and the exception class only.
        assertThat(ports.failedCriticalChecks)
                .containsExactly(receipt.issueId() + ": java.lang.IllegalArgumentException");
        assertThat(ports.failedCriticalChecks.getFirst()).doesNotContain("smoke", "gas");
    }

    @Test
    void aFailedCheckOfASubmissionThatIsRolledBackIsNotLogged() {
        ports.failAppendNumber = 1;
        SubmitReportService failing = service(pt.isep.bmc.issue.domain.CriticalCheckTest.failing());

        assertThatThrownBy(() -> failing.submit(command("k1", GARAGE_A, DANGER)))
                .isInstanceOf(RuntimeException.class);

        assertThat(ports.issues).isEmpty();
        assertThat(ports.failedCriticalChecks).isEmpty();
    }

    @Test
    void aReplayRaisesNoSignalTwice() {
        service.submit(command("k1", GARAGE_A, DANGER));
        service.submit(command("k1", GARAGE_A, DANGER));

        assertThat(ports.criticalSignals).hasSize(2);
        assertThat(ports.history).hasSize(3);
    }

    @Test
    void aFailureAfterTheSignalsWereStoredLeavesNoSignalBehind() {
        ports.failAppendNumber = 2;

        assertThatThrownBy(() -> service.submit(command("k1", GARAGE_A, DANGER)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(ports.reports).isEmpty();
        assertThat(ports.issues).isEmpty();
        assertThat(ports.criticalSignals).isEmpty();
        assertThat(ports.history).isEmpty();
    }

    @Test
    void sameKeyAndBodyReplaysTheOriginalReceiptWithoutNewEffects() {
        ReportReceipt first = service.submit(command("k1", GARAGE_A, TEXT));
        clock.advance(Duration.ofMinutes(5));
        ReportReceipt again = service.submit(command("k1", GARAGE_A, TEXT));

        assertThat(again).isEqualTo(new ReportReceipt(
                first.reportId(), first.issueId(), first.state(), first.version(), first.submittedAt(), true));
        assertThat(ports.reports).hasSize(1);
        assertThat(ports.outbox).hasSize(2);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejectedAndOriginalUnchanged() {
        ReportReceipt first = service.submit(command("k1", GARAGE_A, TEXT));
        assertThatThrownBy(() -> service.submit(command("k1", GARAGE_A, TEXT + " Also the lights.")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(ports.reports).hasSize(1);
        assertThat(ports.reports.get(first.reportId()).text().value()).isEqualTo(TEXT);
    }

    @Test
    void concurrentRetryRollsBackAndReplaysTheWinner() {
        ReportReceipt[] winner = new ReportReceipt[1];
        // The first record() call finds that a concurrent transaction committed first.
        ports.beforeNextRecord = () -> {
            InMemoryPorts other = ports;
            Runnable none = null;
            other.beforeNextRecord = none;
            winner[0] = service(new CriticalAlertsV1()).submit(command("k1", GARAGE_A, TEXT));
        };
        ReportReceipt loser = service.submit(command("k1", GARAGE_A, TEXT));

        assertThat(loser.replayed()).isTrue();
        assertThat(loser.reportId()).isEqualTo(winner[0].reportId());
        assertThat(ports.reports).hasSize(1);
        assertThat(ports.issues).hasSize(1);
        assertThat(ports.outbox).hasSize(2);
    }

    @Test
    void keysAreScopedPerActor() {
        UserId other = new UserId("resident-a2");
        ports.memberships.add(new Membership(new MembershipId(UUID.randomUUID()), other, A, MembershipRole.RESIDENT,
                MembershipStatus.ACTIVE, NOW.minusSeconds(3600), null, 1));
        service.submit(command("shared-key", GARAGE_A, TEXT));
        service.submit(new SubmitReportCommand(other, new IdempotencyKey("shared-key"), A, GARAGE_A.id(), TEXT, "c-2"));
        assertThat(ports.reports).hasSize(2);
    }

    @Test
    void expiredKeyIsTreatedAsNew() {
        service.submit(command("k1", GARAGE_A, TEXT));
        clock.advance(Duration.ofHours(24));
        ReportReceipt second = service.submit(command("k1", GARAGE_A, TEXT + " Again."));
        assertThat(second.replayed()).isFalse();
        assertThat(ports.reports).hasSize(2);
    }

    @Test
    void rejectionsLeaveNothingBehind() {
        assertThatThrownBy(() -> service.submit(command("k1", GARAGE_B, TEXT))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.submit(new SubmitReportCommand(
                RESIDENT, new IdempotencyKey("k2"), A, GARAGE_B.id(), TEXT, "c")))
                .isInstanceOf(InvalidAreaException.class);
        assertThatThrownBy(() -> service.submit(command("k3", GARAGE_A, " too short ")))
                .isInstanceOf(ValidationException.class);
        assertNothingStored();
    }

    @Test
    void storageFailureGivesNoReceiptAndStoresNothing() {
        ports.failNextTransaction = new StorageUnavailableException(new RuntimeException("connection refused"));
        assertThatThrownBy(() -> service.submit(command("k1", GARAGE_A, TEXT)))
                .isInstanceOf(StorageUnavailableException.class);
        assertNothingStored();
        // The client retries with the same key once storage is back.
        assertThat(service.submit(command("k1", GARAGE_A, TEXT)).replayed()).isFalse();
    }

    @Test
    void purgeRemovesOnlyExpiredKeys() {
        service.submit(command("old", GARAGE_A, TEXT));
        clock.advance(Duration.ofHours(23));
        service.submit(command("new", GARAGE_A, TEXT));
        clock.advance(Duration.ofHours(1));
        int purged = new PurgeExpiredIdempotencyKeysService(ports, ports, clock, 500).purge();
        assertThat(purged).isEqualTo(1);
        assertThat(ports.keys.keySet()).extracting(s -> s.key().value()).containsExactly("new");
    }

    @Test
    void purgeWorksInBatchesUntilNothingExpiredIsLeft() {
        for (int i = 0; i < 1_001; i++) {
            service.submit(command("bulk-" + i, GARAGE_A, TEXT));
        }
        clock.advance(Duration.ofHours(25));
        assertThat(new PurgeExpiredIdempotencyKeysService(ports, ports, clock, 500).purge()).isEqualTo(1_001);
        assertThat(ports.keys).isEmpty();
    }

    @Test
    void idempotencyKeyFormatIsValidated() {
        assertThatThrownBy(() -> new IdempotencyKey("")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new IdempotencyKey("has space")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new IdempotencyKey("x".repeat(129))).isInstanceOf(ValidationException.class);
        assertThat(new IdempotencyKey(UUID.randomUUID().toString()).value()).hasSize(36);
    }

    private void assertNothingStored() {
        assertThat(ports.reports).isEmpty();
        assertThat(ports.issues).isEmpty();
        assertThat(ports.history).isEmpty();
        assertThat(ports.outbox).isEmpty();
        assertThat(ports.keys).isEmpty();
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
