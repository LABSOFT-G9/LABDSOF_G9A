package pt.isep.bmc.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.dispatch.application.ApplicationFixtures.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.application.InMemoryPorts.MutableClock;
import pt.isep.bmc.dispatch.application.command.ContractorReplaced;
import pt.isep.bmc.dispatch.application.command.CreateInterventionCommand;
import pt.isep.bmc.dispatch.application.command.CreateInterventionService;
import pt.isep.bmc.dispatch.application.command.ReplaceContractorCommand;
import pt.isep.bmc.dispatch.application.command.ReplaceContractorService;
import pt.isep.bmc.dispatch.application.error.IdempotencyKeyReusedException;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.FieldViolation;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;
import pt.isep.bmc.dispatch.domain.shared.VersionConflictException;

/** US-06: replacing the contractor of a draft, its check order (README, decision 6) and its idempotency. */
class ReplaceContractorServiceTest {

    InMemoryPorts ports;
    MutableClock clock;
    ReplaceContractorService service;
    IssueId issueId;
    InterventionId interventionId;

    @BeforeEach
    void setUp() {
        ports = ports();
        clock = new MutableClock(NOW);
        issueId = newIssueId();
        ports.issues.put(issueId, triagedPlumbing(issueId));
        interventionId = new CreateInterventionService(ports, ports, ports, ports, ports, ports, ports, clock)
                .create(new CreateInterventionCommand(ADMIN_A, new IdempotencyKey("draft"), issueId,
                        PLUMBER.id().toString(), SCOPE, WINDOW_START, WINDOW_END, TERMS))
                .interventionId();
        ports.keys.clear();
        clock.advance(Duration.ofMinutes(1));
        service = new ReplaceContractorService(ports, ports, ports, ports, ports, ports, clock);
    }

    ReplaceContractorCommand command(UserId actor, String key, long expectedVersion, String contractorId) {
        return new ReplaceContractorCommand(actor, new IdempotencyKey(key), interventionId, expectedVersion,
                contractorId);
    }

    ReplaceContractorCommand valid(String key) {
        return command(ADMIN_A, key, 1, OTHER_PLUMBER.id().toString());
    }

    Intervention stored() {
        return ports.interventions.get(interventionId);
    }

    void givenTheDraftIs(InterventionState state) {
        Intervention i = stored();
        ports.interventions.put(interventionId, new Intervention(i.id(), i.issueId(), i.buildingId(),
                i.contractorId(), state, i.version(), i.currentProposalVersion(), i.projectedIssueVersion(),
                i.projectionAppliedAt(), i.createdBy(), i.createdAt(), i.updatedAt()));
    }

    void assertUnchanged() {
        assertThat(stored().version()).isEqualTo(1);
        assertThat(stored().contractorId()).isEqualTo(PLUMBER.id());
        assertThat(ports.keys).as("a rejected request leaves no key").isEmpty();
    }

    @Test
    void replacesTheContractorMovesTheVersionAndKeepsTheTerms() {
        ContractorReplaced result = service.replace(valid("k1"));

        assertThat(result).isEqualTo(new ContractorReplaced(interventionId, InterventionState.AWAITING_APPROVAL, 2,
                OTHER_PLUMBER.id(), false));
        assertThat(stored().contractorId()).isEqualTo(OTHER_PLUMBER.id());
        assertThat(stored().currentProposalVersion()).isEqualTo(1);
        assertThat(stored().updatedAt()).isAfter(stored().createdAt());
        assertThat(ports.proposals).hasSize(1);
        assertThat(ports.keys.values()).singleElement().satisfies(record -> {
            assertThat(record.responseStatus()).isEqualTo(200);
            assertThat(record.resourceVersion()).isEqualTo(2);
        });
    }

    @Test
    void savingTheSameContractorAgainStillMovesTheVersion() {
        assertThat(service.replace(command(ADMIN_A, "k1", 1, PLUMBER.id().toString())).version()).isEqualTo(2);
    }

    @Test
    void step1ACallerWhoDoesNotAdministerTheBuildingOrAnUnknownDraftIs404BeforeTheContractorId() {
        for (UserId other : List.of(RESIDENT_A1, ADMIN_B, new UserId("someone-else"))) {
            assertThatThrownBy(() -> service.replace(command(other, "k1", 1, "x"))).as(other.value())
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> service.replace(new ReplaceContractorCommand(ADMIN_A, new IdempotencyKey("k1"),
                new InterventionId(UUID.randomUUID()), 1, "x"))).isInstanceOf(AccessDeniedException.class);
        assertUnchanged();
    }

    @Test
    void step2AnInvalidContractorIdIs400BeforeTheState() {
        givenTheDraftIs(InterventionState.CANCELLED);

        assertThatThrownBy(() -> service.replace(command(ADMIN_A, "k1", 7, "x")))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("contractorId", "invalid_format")));
    }

    @Test
    void step3ADraftNoLongerAwaitingApprovalIs409BeforeTheVersion() {
        givenTheDraftIs(InterventionState.SENT);

        assertThatThrownBy(() -> service.replace(command(ADMIN_A, "k1", 7, OTHER_PLUMBER.id().toString())))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("sent"));
    }

    @Test
    void step4AStaleVersionIs409WithTheCurrentOneBeforeTheIssueState() {
        ports.issues.put(issueId, issue(issueId, "cancelled", 4, false));

        assertThatThrownBy(() -> service.replace(command(ADMIN_A, "k1", 7, ELECTRICIAN.id().toString())))
                .isInstanceOfSatisfying(VersionConflictException.class, e -> {
                    assertThat(e.currentVersion()).isEqualTo(1);
                    assertThat(e.currentState()).isEqualTo("awaiting_approval");
                });
        assertUnchanged();
    }

    @Test
    void step5AnIssueNoLongerDispatchableIs409WithTheIssuesStateBeforeEligibility() {
        ports.issues.put(issueId, issue(issueId, "cancelled", 4, false));

        assertThatThrownBy(() -> service.replace(command(ADMIN_A, "k1", 1, ELECTRICIAN.id().toString())))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).as("the issue's state").isEqualTo("cancelled"));
        assertUnchanged();
        assertThat(stored().state()).isEqualTo(InterventionState.AWAITING_APPROVAL);
    }

    @Test
    void step6AnIneligibleOrUnknownContractorAnswersTheSame() {
        for (String contractorId : List.of(ELECTRICIAN.id().toString(), "0199f2a0-0000-7000-8000-00000000e999")) {
            assertThatThrownBy(() -> service.replace(command(ADMIN_A, "k-" + contractorId, 1, contractorId)))
                    .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                            .containsExactly(new FieldViolation("contractorId", "not_eligible")));
        }
        assertUnchanged();
    }

    @Test
    void theSameKeyAndBodyReplayTheResultWithoutAnotherChange() {
        ContractorReplaced first = service.replace(valid("k1"));
        clock.advance(Duration.ofMinutes(5));

        ContractorReplaced again = service.replace(valid("k1"));

        assertThat(again).isEqualTo(new ContractorReplaced(first.interventionId(), first.state(), first.version(),
                first.contractorId(), true));
        assertThat(stored().version()).isEqualTo(2);
    }

    @Test
    void aReplayReChecksTheCallersAccess() {
        service.replace(valid("k1"));
        ports.memberships.put(MEMBERSHIP_ADMIN_A,
                membership(MEMBERSHIP_ADMIN_A, ADMIN_A, BUILDING_A, "administrator", false, 2));

        assertThatThrownBy(() -> service.replace(valid("k1"))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theSameKeyWithAnotherBodyIsRefusedAndTheDraftIsUnchanged() {
        service.replace(valid("k1"));

        assertThatThrownBy(() -> service.replace(command(ADMIN_A, "k1", 1, PLUMBER.id().toString())))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(stored().contractorId()).isEqualTo(OTHER_PLUMBER.id());
        assertThat(stored().version()).isEqualTo(2);
    }

    @Test
    void aConcurrentRetryRollsBackAndReplaysTheWinner() {
        ContractorReplaced[] winner = new ContractorReplaced[1];
        ports.beforeNextRecord = () -> winner[0] = service.replace(valid("k1"));

        ContractorReplaced loser = service.replace(valid("k1"));

        assertThat(winner[0].replayed()).isFalse();
        assertThat(loser.replayed()).isTrue();
        assertThat(loser.version()).isEqualTo(winner[0].version());
        assertThat(stored().version()).isEqualTo(2);
    }
}
