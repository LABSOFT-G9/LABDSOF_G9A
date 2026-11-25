package pt.isep.bmc.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.application.command.DecideTriageCommand;
import pt.isep.bmc.issue.application.command.DecideTriageService;
import pt.isep.bmc.issue.application.command.TriageReceipt;
import pt.isep.bmc.issue.application.error.IdempotencyKeyReusedException;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.application.error.StorageUnavailableException;
import pt.isep.bmc.issue.application.idempotency.IdempotencyKey;
import pt.isep.bmc.issue.application.port.out.OutboxEvent;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.AreaKind;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.event.IssueStateChanged;
import pt.isep.bmc.issue.domain.event.IssueTriaged;
import pt.isep.bmc.issue.domain.issue.HistoryEntryType;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;
import pt.isep.bmc.issue.domain.membership.Membership;
import pt.isep.bmc.issue.domain.membership.MembershipId;
import pt.isep.bmc.issue.domain.membership.MembershipRole;
import pt.isep.bmc.issue.domain.membership.MembershipStatus;
import pt.isep.bmc.issue.domain.membership.UserId;
import pt.isep.bmc.issue.domain.report.Report;
import pt.isep.bmc.issue.domain.report.ReportId;
import pt.isep.bmc.issue.domain.report.ReportSource;
import pt.isep.bmc.issue.domain.report.ReportText;
import pt.isep.bmc.issue.domain.shared.AccessDeniedException;
import pt.isep.bmc.issue.domain.shared.ForbiddenException;
import pt.isep.bmc.issue.domain.shared.InvalidTransitionException;
import pt.isep.bmc.issue.domain.shared.ValidationException;
import pt.isep.bmc.issue.domain.shared.VersionConflictException;

class DecideTriageServiceTest {

    static final Instant NOW = Instant.parse("2026-11-24T10:00:00.123456Z");
    static final Instant NOW_MILLIS = Instant.parse("2026-11-24T10:00:00.123Z");
    static final UserId ADMIN = new UserId("admin-a");
    static final UserId OTHER_ADMIN = new UserId("admin-a2");
    static final UserId RESIDENT = new UserId("resident-a");
    static final BuildingId A = new BuildingId(UUID.randomUUID());
    static final Area GARAGE = new Area(new AreaId(UUID.randomUUID()), A, "Garage level -1", AreaKind.COMMON_AREA);
    static final String REASON = "Water near an electrical fitting";

    InMemoryPorts ports;
    DecideTriageService service;
    Issue issue;

    @BeforeEach
    void setUp() {
        ports = new InMemoryPorts();
        ports.memberships.add(membership(ADMIN, MembershipRole.ADMINISTRATOR));
        ports.memberships.add(membership(OTHER_ADMIN, MembershipRole.ADMINISTRATOR));
        ports.memberships.add(membership(RESIDENT, MembershipRole.RESIDENT));
        ports.areas.put(GARAGE.id(), GARAGE);
        issue = Issue.receive(new IssueId(UUID.randomUUID()), A, GARAGE.id(), NOW.minusSeconds(600));
        ports.issues.add(issue);
        ports.history.add(IssueHistoryEntry.reportReceived(issue.id(), ReportSource.RESIDENT, issue.createdAt()));
        service = new DecideTriageService(ports, ports, ports, ports, ports, ports, ports, ports, ports,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static Membership membership(UserId user, MembershipRole role) {
        return new Membership(new MembershipId(UUID.randomUUID()), user, A, role, MembershipStatus.ACTIVE,
                NOW.minusSeconds(3600), null, 1);
    }

    DecideTriageCommand command(UserId actor, String key, long version, String category, String urgency, String reason) {
        return new DecideTriageCommand(actor, new IdempotencyKey(key), issue.id(), version, category, urgency, reason, "c-7");
    }

    Issue stored() {
        return ports.issues.get(0);
    }

    @Test
    void storesTheDecisionTheNextVersionHistoryAndBothEventsTogether() {
        TriageReceipt receipt = service.decide(command(ADMIN, "k1", 1, "plumbing", "high", REASON));

        assertThat(receipt).isEqualTo(new TriageReceipt(issue.id(), IssueState.TRIAGED, 2, IssueCategory.PLUMBING,
                IssueUrgency.HIGH, false));
        assertThat(stored().state()).isEqualTo(IssueState.TRIAGED);
        assertThat(stored().version()).isEqualTo(2);
        assertThat(stored().category()).contains(IssueCategory.PLUMBING);

        assertThat(ports.decisions).singleElement().satisfies(decision -> {
            assertThat(decision.decidedBy()).isEqualTo(ADMIN);
            assertThat(decision.reason().value()).isEqualTo(REASON);
            assertThat(decision.resultingVersion()).isEqualTo(2);
            assertThat(decision.decidedAt()).isEqualTo(NOW_MILLIS);
        });
        assertThat(ports.history).hasSize(2);
        assertThat(ports.history.get(1).type()).isEqualTo(HistoryEntryType.TRIAGE_DECIDED);
        assertThat(ports.history.get(1).sequence()).isEqualTo(2);
        assertThat(ports.history.get(1).issueVersion()).isEqualTo(2);

        assertThat(ports.outbox).extracting(e -> e.event().type()).containsExactly("IssueTriaged", "IssueStateChanged");
        assertThat(ports.outbox).extracting(OutboxEvent::correlationId).containsOnly("c-7");
        assertThat(ports.outbox.get(0).event()).isInstanceOf(IssueTriaged.class);
        assertThat(ports.outbox.get(1).event()).isInstanceOfSatisfying(IssueStateChanged.class, event -> {
            assertThat(event.dispatchable()).isTrue();
            assertThat(event.areaLabel()).isEqualTo("Garage level -1");
            assertThat(event.aggregateVersion()).isEqualTo(2);
        });
        assertThat(ports.keys).hasSize(1);
    }

    @Test
    void aDecisionCanBeCorrectedAndBothDecisionsAreKept() {
        service.decide(command(ADMIN, "k1", 1, "plumbing", "high", REASON));
        TriageReceipt corrected = service.decide(command(OTHER_ADMIN, "k2", 2, "electrical", "critical", "It is the socket"));

        assertThat(corrected.version()).isEqualTo(3);
        assertThat(stored().category()).contains(IssueCategory.ELECTRICAL);
        assertThat(ports.decisions).extracting(d -> d.resultingVersion()).containsExactly(2L, 3L);
        assertThat(ports.history).extracting(IssueHistoryEntry::sequence).containsExactly(1, 2, 3);
    }

    @Test
    void theSameKeyAndRequestReplayTheReceiptEvenThoughTheVersionMovedOn() {
        TriageReceipt first = service.decide(command(ADMIN, "k1", 1, "plumbing", "high", REASON));
        // The client retries with the same If-Match "1": not a conflict, the original answer.
        TriageReceipt retry = service.decide(command(ADMIN, "k1", 1, "plumbing", "high", REASON));

        assertThat(retry).isEqualTo(new TriageReceipt(first.issueId(), IssueState.TRIAGED, 2, IssueCategory.PLUMBING,
                IssueUrgency.HIGH, true));
        assertThat(ports.decisions).hasSize(1);
        assertThat(ports.history).hasSize(2);
        assertThat(ports.outbox).hasSize(2);

        // Still the first decision's receipt after someone corrected it.
        service.decide(command(OTHER_ADMIN, "k2", 2, "electrical", "critical", REASON));
        assertThat(service.decide(command(ADMIN, "k1", 1, "plumbing", "high", REASON)).version()).isEqualTo(2);
    }

    @Test
    void aRetryIsAnsweredByTheCallersPermissionsNow() {
        service.decide(command(ADMIN, "k1", 1, "plumbing", "high", REASON));
        revoke(ADMIN);

        // Not an administrator any more and never reported it: the issue does not exist for this caller.
        assertThatThrownBy(() -> service.decide(command(ADMIN, "k1", 1, "plumbing", "high", REASON)))
                .isInstanceOf(AccessDeniedException.class);

        // The same person also reported it, so they still see it, but may not decide or read the decision again.
        ReportId reportId = new ReportId(UUID.randomUUID());
        ports.reports.put(reportId, Report.restore(reportId, issue.id(), A, GARAGE.id(), ADMIN,
                ReportText.restore("Water dripping in the garage."), ReportSource.RESIDENT, issue.createdAt()));
        assertThatThrownBy(() -> service.decide(command(ADMIN, "k1", 1, "plumbing", "high", REASON)))
                .isInstanceOf(ForbiddenException.class);

        assertThat(ports.decisions).hasSize(1);
        assertThat(ports.history).hasSize(2);
        assertThat(ports.outbox).hasSize(2);
        assertThat(ports.keys).hasSize(1);
        // Someone who still administers the building is not affected.
        assertThat(service.decide(command(OTHER_ADMIN, "k2", 2, "electrical", "low", REASON)).replayed()).isFalse();
        assertThat(service.decide(command(OTHER_ADMIN, "k2", 2, "electrical", "low", REASON)).replayed()).isTrue();
    }

    void revoke(UserId user) {
        ports.memberships.replaceAll(m -> m.userId().equals(user)
                ? new Membership(m.id(), user, A, m.role(), MembershipStatus.REVOKED, m.validFrom(), null, 2)
                : m);
    }

    @Test
    void theSameKeyWithADifferentRequestIsRefusedAndChangesNothing() {
        service.decide(command(ADMIN, "k1", 1, "plumbing", "high", REASON));
        assertThatThrownBy(() -> service.decide(command(ADMIN, "k1", 1, "plumbing", "low", REASON)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> service.decide(command(ADMIN, "k1", 2, "plumbing", "high", REASON)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(stored().version()).isEqualTo(2);
        assertThat(ports.decisions).hasSize(1);
    }

    @Test
    void aConcurrentRetryThatLosesTheRaceReplaysTheWinner() {
        DecideTriageCommand command = command(ADMIN, "k1", 1, "plumbing", "high", REASON);
        // Another request with the same key commits just before this one records its key.
        ports.beforeNextRecord = () -> service.decide(command);

        TriageReceipt receipt = service.decide(command);

        assertThat(receipt.replayed()).isTrue();
        assertThat(receipt.version()).isEqualTo(2);
        assertThat(ports.decisions).hasSize(1);
        assertThat(ports.outbox).hasSize(2);
        assertThat(stored().version()).isEqualTo(2);
    }

    @Test
    void aStaleVersionChangesNothingAndLeavesNoKey() {
        service.decide(command(ADMIN, "k1", 1, "plumbing", "high", REASON));
        assertThatThrownBy(() -> service.decide(command(OTHER_ADMIN, "k2", 1, "electrical", "low", REASON)))
                .isInstanceOfSatisfying(VersionConflictException.class, e -> {
                    assertThat(e.currentVersion()).isEqualTo(2);
                    assertThat(e.currentState()).isEqualTo("triaged");
                });
        assertThat(stored().category()).contains(IssueCategory.PLUMBING);
        assertThat(ports.decisions).hasSize(1);
        assertThat(ports.history).hasSize(2);
        assertThat(ports.outbox).hasSize(2);
        assertThat(ports.keys).as("the rejected request's key was rolled back").hasSize(1);
        // The same key is therefore free to carry the corrected request.
        assertThat(service.decide(command(OTHER_ADMIN, "k2", 2, "electrical", "low", REASON)).version()).isEqualTo(3);
    }

    @Test
    void rejectedRequestsStoreNothingAtAll() {
        assertThatThrownBy(() -> service.decide(command(ADMIN, "k1", 1, "gardening", "high", REASON)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.decide(command(RESIDENT, "k2", 1, "plumbing", "high", REASON)))
                .as("a resident of the building who did not report it cannot even see it")
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.decide(command(new UserId("stranger"), "k3", 1, "plumbing", "high", REASON)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.decide(new DecideTriageCommand(ADMIN, new IdempotencyKey("k4"),
                new IssueId(UUID.randomUUID()), 1, "plumbing", "high", REASON, "c-7")))
                .isInstanceOf(NotFoundException.class);

        assertThat(stored()).isSameAs(issue);
        assertThat(ports.decisions).isEmpty();
        assertThat(ports.history).hasSize(1);
        assertThat(ports.outbox).isEmpty();
        assertThat(ports.keys).isEmpty();
    }

    @Test
    void theResidentWhoReportedTheIssueIsForbiddenNotBlind() {
        ReportId reportId = new ReportId(UUID.randomUUID());
        ports.reports.put(reportId, Report.restore(reportId, issue.id(), A, GARAGE.id(), RESIDENT,
                ReportText.restore("Water dripping in the garage."), ReportSource.RESIDENT, issue.createdAt()));
        assertThatThrownBy(() -> service.decide(command(RESIDENT, "k1", 1, "plumbing", "high", REASON)))
                .isInstanceOf(ForbiddenException.class);
        assertThat(ports.keys).isEmpty();
        assertThat(stored()).isSameAs(issue);
    }

    @Test
    void triageIsRefusedOnceTheIssueIsInCoordination() {
        Issue coordinating = Issue.restore(issue.id(), A, GARAGE.id(), IssueState.IN_COORDINATION, 5,
                IssueCategory.PLUMBING, IssueUrgency.HIGH, issue.criticalCheckStatus(), issue.createdAt(), issue.createdAt());
        ports.issues.set(0, coordinating);
        assertThatThrownBy(() -> service.decide(command(ADMIN, "k1", 5, "electrical", "low", REASON)))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("in_coordination"));
        assertThat(ports.outbox).isEmpty();
        assertThat(ports.keys).isEmpty();
    }

    @Test
    void aFailureWhileWritingRollsEverythingBack() {
        ports.failAppendNumber = 2; // the second outbox row fails
        assertThatThrownBy(() -> service.decide(command(ADMIN, "k1", 1, "plumbing", "high", REASON)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(stored()).isSameAs(issue);
        assertThat(ports.decisions).isEmpty();
        assertThat(ports.history).hasSize(1);
        assertThat(ports.outbox).isEmpty();
        assertThat(ports.keys).isEmpty();

        ports.failNextTransaction = new StorageUnavailableException(new RuntimeException("db down"));
        assertThatThrownBy(() -> service.decide(command(ADMIN, "k1", 1, "plumbing", "high", REASON)))
                .isInstanceOf(StorageUnavailableException.class);
        assertThat(ports.keys).isEmpty();
    }

    @Test
    void theCommandNeverPrintsTheReason() {
        assertThat(command(ADMIN, "k1", 1, "plumbing", "high", REASON).toString()).doesNotContain("Water");
    }
}
