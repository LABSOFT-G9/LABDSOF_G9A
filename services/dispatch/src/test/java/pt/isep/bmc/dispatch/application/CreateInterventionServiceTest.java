package pt.isep.bmc.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.dispatch.application.ApplicationFixtures.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.application.InMemoryPorts.MutableClock;
import pt.isep.bmc.dispatch.application.command.CreateInterventionCommand;
import pt.isep.bmc.dispatch.application.command.CreateInterventionService;
import pt.isep.bmc.dispatch.application.command.DraftReceipt;
import pt.isep.bmc.dispatch.application.error.IdempotencyKeyReusedException;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.FieldViolation;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;

/** US-06: saving a draft request, its check order (README, decision 6) and its idempotency. */
class CreateInterventionServiceTest {

    InMemoryPorts ports;
    MutableClock clock;
    CreateInterventionService service;
    IssueId issueId;

    @BeforeEach
    void setUp() {
        ports = ports();
        clock = new MutableClock(NOW);
        service = new CreateInterventionService(ports, ports, ports, ports, ports, ports, ports, clock);
        issueId = newIssueId();
        ports.issues.put(issueId, triagedPlumbing(issueId));
    }

    CreateInterventionCommand command(UserId actor, String key, IssueId issue, String contractorId, String scope) {
        return new CreateInterventionCommand(actor, new IdempotencyKey(key), issue, contractorId, scope, WINDOW_START,
                WINDOW_END, TERMS);
    }

    CreateInterventionCommand valid(String key) {
        return command(ADMIN_A, key, issueId, PLUMBER.id().toString(), SCOPE);
    }

    /** Invalid in every field the domain validates. */
    CreateInterventionCommand invalid(UserId actor, IssueId issue) {
        return new CreateInterventionCommand(actor, new IdempotencyKey("bad"), issue, "x", "short", null, "later", "");
    }

    void assertNothingStored() {
        assertThat(ports.interventions).isEmpty();
        assertThat(ports.proposals).isEmpty();
        assertThat(ports.keys).as("a rejected request leaves no key").isEmpty();
    }

    @Test
    void storesTheDraftItsFirstProposalAndTheKeyTogether() {
        DraftReceipt receipt = service.create(valid("k1"));

        assertThat(receipt.state()).isEqualTo(InterventionState.AWAITING_APPROVAL);
        assertThat(receipt.version()).isEqualTo(1);
        assertThat(receipt.proposalVersion()).isEqualTo(1);
        assertThat(receipt.contractorId()).isEqualTo(PLUMBER.id());
        assertThat(receipt.replayed()).isFalse();
        assertThat(ports.interventions.get(receipt.interventionId())).satisfies(saved -> {
            assertThat(saved.issueId()).isEqualTo(issueId);
            assertThat(saved.createdBy()).isEqualTo(ADMIN_A);
            assertThat(saved.createdAt()).as("millisecond precision").isEqualTo(Instant.parse("2026-11-26T10:00:00.123Z"));
            assertThat(saved.projectedIssueVersion()).isEqualTo(3);
        });
        assertThat(ports.proposals).singleElement().satisfies(proposal -> {
            assertThat(proposal.number()).isEqualTo(1);
            assertThat(proposal.scopeExtract()).isEqualTo(SCOPE);
            assertThat(proposal.terms()).isEqualTo(TERMS);
        });
        assertThat(ports.keys.values()).singleElement().satisfies(record -> {
            assertThat(record.responseStatus()).isEqualTo(201);
            assertThat(record.resourceId()).isEqualTo(receipt.interventionId().value());
            assertThat(record.expiresAt()).isEqualTo(record.createdAt().plus(Duration.ofHours(24)));
        });
    }

    @Test
    void step1AnUnknownIssueOrACallerWhoDoesNotAdministerItIs404BeforeTheFields() {
        assertThatThrownBy(() -> service.create(invalid(ADMIN_A, newIssueId())))
                .isInstanceOf(AccessDeniedException.class);
        for (UserId other : List.of(RESIDENT_A1, ADMIN_B, new UserId("someone-else"))) {
            assertThatThrownBy(() -> service.create(invalid(other, issueId))).as(other.value())
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertNothingStored();
    }

    @Test
    void step2EveryInvalidFieldAtOnceBeforeTheIssueState() {
        ports.issues.put(issueId, issue(issueId, "in_triage", 2, false));

        assertThatThrownBy(() -> service.create(invalid(ADMIN_A, issueId)))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .extracting(FieldViolation::field)
                        .containsExactlyInAnyOrder("contractorId", "scopeExtract", "window.start", "window.end",
                                "terms"));
        assertNothingStored();
    }

    @Test
    void step3AnIssueThatIsNotDispatchableIs409WithItsStateBeforeEligibility() {
        ports.issues.put(issueId, issue(issueId, "in_triage", 2, false));

        assertThatThrownBy(() -> service.create(command(ADMIN_A, "k1", issueId, ELECTRICIAN.id().toString(), SCOPE)))
                .isInstanceOfSatisfying(InvalidTransitionException.class, e -> {
                    assertThat(e.currentState()).isEqualTo("in_triage");
                    assertThat(e.activeInterventionId()).isEmpty();
                });
        assertNothingStored();
    }

    @Test
    void step4AnotherActiveInterventionIs409WithItsIdBeforeEligibility() {
        DraftReceipt first = service.create(valid("k1"));

        assertThatThrownBy(() -> service.create(command(ADMIN_A, "k2", issueId, ELECTRICIAN.id().toString(), SCOPE)))
                .isInstanceOfSatisfying(InvalidTransitionException.class, e -> {
                    assertThat(e.currentState()).isEqualTo("triaged");
                    assertThat(e.activeInterventionId()).contains(first.interventionId().value());
                });
        assertThat(ports.interventions).hasSize(1);
        assertThat(ports.keys).hasSize(1);
    }

    @Test
    void step5AnIneligibleOrUnknownContractorAnswersTheSame() {
        String unknown = "0199f2a0-0000-7000-8000-00000000e999";
        for (String contractorId : List.of(ELECTRICIAN.id().toString(), unknown)) {
            assertThatThrownBy(() -> service.create(command(ADMIN_A, "k-" + contractorId, issueId, contractorId,
                    SCOPE)))
                    .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                            .containsExactly(new FieldViolation("contractorId", "not_eligible")));
        }
        assertNothingStored();
    }

    @Test
    void theSameKeyAndBodyReplayTheReceiptWithoutANewDraft() {
        DraftReceipt first = service.create(valid("k1"));
        clock.advance(Duration.ofMinutes(5));

        DraftReceipt again = service.create(valid("k1"));

        assertThat(again).isEqualTo(new DraftReceipt(first.interventionId(), first.state(), first.version(),
                first.proposalVersion(), first.contractorId(), true));
        assertThat(ports.interventions).hasSize(1);
        assertThat(ports.proposals).hasSize(1);
    }

    @Test
    void aReplayReChecksTheCallersAccess() {
        service.create(valid("k1"));
        ports.memberships.put(MEMBERSHIP_ADMIN_A,
                membership(MEMBERSHIP_ADMIN_A, ADMIN_A, BUILDING_A, "administrator", false, 2));

        assertThatThrownBy(() -> service.create(valid("k1"))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theSameKeyWithAnotherBodyIsRefusedAndTheDraftIsUnchanged() {
        service.create(valid("k1"));

        assertThatThrownBy(() -> service.create(command(ADMIN_A, "k1", issueId, OTHER_PLUMBER.id().toString(), SCOPE)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(ports.interventions.values()).singleElement()
                .satisfies(saved -> assertThat(saved.contractorId()).isEqualTo(PLUMBER.id()));
    }

    @Test
    void aConcurrentRetryRollsBackAndReplaysTheWinner() {
        DraftReceipt[] winner = new DraftReceipt[1];
        // The first record() finds that a concurrent transaction with the same key committed first.
        ports.beforeNextRecord = () -> winner[0] = service.create(valid("k1"));

        DraftReceipt loser = service.create(valid("k1"));

        assertThat(loser.replayed()).isTrue();
        assertThat(loser.interventionId()).isEqualTo(winner[0].interventionId());
        assertThat(ports.interventions).hasSize(1);
        assertThat(ports.keys).hasSize(1);
    }

    @Test
    void anExpiredKeyIsFreeAgain() {
        DraftReceipt first = service.create(valid("k1"));
        ports.interventions.clear();
        clock.advance(Duration.ofHours(24));

        DraftReceipt second = service.create(valid("k1"));

        assertThat(second.replayed()).isFalse();
        assertThat(second.interventionId()).isNotEqualTo(first.interventionId());
    }
}
