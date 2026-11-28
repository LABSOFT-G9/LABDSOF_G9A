package pt.isep.bmc.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.dispatch.application.ApplicationFixtures.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.application.command.ResponseRecorded;
import pt.isep.bmc.dispatch.application.error.IdempotencyKeyReusedException;
import pt.isep.bmc.dispatch.domain.event.ContractorResponded;
import pt.isep.bmc.dispatch.domain.event.InterventionApproved;
import pt.isep.bmc.dispatch.domain.event.InterventionDeclined;
import pt.isep.bmc.dispatch.domain.intervention.DeclineReason;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.intervention.Money;
import pt.isep.bmc.dispatch.domain.intervention.ResponseDecision;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.FieldViolation;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.UserId;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;
import pt.isep.bmc.dispatch.domain.shared.VersionConflictException;

/** US-07: the assigned contractor's answer, its events and its check order. */
class RespondToRequestServiceTest {

    AgreementFixtures f = new AgreementFixtures();

    @BeforeEach
    void sent() {
        f.sent();
        f.ports.keys.clear();
    }

    void assertNothingChanged() {
        assertThat(f.stored().version()).isEqualTo(2);
        assertThat(f.stored().state()).isEqualTo(InterventionState.SENT);
        assertThat(f.ports.responses).isEmpty();
        assertThat(f.ports.proposals).hasSize(1);
        assertThat(f.ports.outbox).as("only the approval's event").hasSize(1);
        assertThat(f.ports.keys).as("a rejected request leaves no key").isEmpty();
    }

    @Test
    void acceptanceAgreesTheVersionAndWritesContractorResponded() {
        ResponseRecorded result = f.responses.respond(f.accept("k1", 2, 1));

        assertThat(result).isEqualTo(new ResponseRecorded(f.interventionId, InterventionState.SCHEDULING_PENDING, 3,
                1, false));
        assertThat(f.stored().agreedProposalVersion()).contains(1);
        assertThat(f.ports.responses).singleElement().satisfies(r -> {
            assertThat(r.decision()).isEqualTo(ResponseDecision.ACCEPT);
            assertThat(r.respondedBy()).isEqualTo(CONTRACTOR_LOGIN);
            assertThat(r.interventionVersion()).isEqualTo(3);
        });
        assertThat(f.ports.outbox.getLast().event()).isInstanceOfSatisfying(ContractorResponded.class, e -> {
            assertThat(e.decision()).isEqualTo(ResponseDecision.ACCEPT);
            assertThat(e.proposalVersion()).isEqualTo(1);
            assertThat(e.aggregateVersion()).isEqualTo(3);
            assertThat(e.interventionState()).isEqualTo(InterventionState.SCHEDULING_PENDING);
        });
    }

    @Test
    void aDeclineIsTerminalAndItsEventCarriesTheCodeButNeverTheNote() {
        ResponseRecorded result = f.responses.respond(f.decline("k1", 2, 1, "unavailable", "Away until March."));

        assertThat(result.state()).isEqualTo(InterventionState.DECLINED);
        assertThat(f.ports.findActiveForIssue(f.issueId)).as("the issue may get another intervention").isEmpty();
        assertThat(f.ports.responses).singleElement().satisfies(r -> {
            assertThat(r.reason()).contains(DeclineReason.UNAVAILABLE);
            assertThat(r.note()).contains("Away until March.");
        });
        assertThat(f.ports.outbox).hasSize(2);
        assertThat(f.ports.outbox.getLast().event()).isInstanceOfSatisfying(InterventionDeclined.class, e -> {
            assertThat(e.reasonCode()).isEqualTo(DeclineReason.UNAVAILABLE);
            assertThat(e.interventionState()).isEqualTo(InterventionState.DECLINED);
        });
        assertThat(f.ports.outbox.getLast().event().toString()).doesNotContain("Away until March.");
    }

    @Test
    void aCounterproposalNeedsApprovalAndApprovingItAgreesWithoutASecondAcceptance() {
        ResponseRecorded result = f.responses.respond(f.counterpropose("k1", 2, 1));

        assertThat(result).isEqualTo(new ResponseRecorded(f.interventionId, InterventionState.AWAITING_APPROVAL, 3,
                2, false));
        assertThat(f.ports.findProposal(f.interventionId, 2)).hasValueSatisfying(p -> {
            assertThat(p.byContractor()).isTrue();
            assertThat(p.createdBy()).isEqualTo(CONTRACTOR_LOGIN);
            assertThat(p.scopeExtract()).isEqualTo(SCOPE);
            assertThat(p.costEstimate()).contains(new Money(new BigDecimal("120.00"), "EUR"));
        });
        assertThat(f.ports.outbox.getLast().event()).isInstanceOfSatisfying(ContractorResponded.class,
                e -> assertThat(e.decision()).isEqualTo(ResponseDecision.COUNTERPROPOSE));

        var approved = f.approval.approve(f.approve(ADMIN_A, "approve-2", 3, 2));

        assertThat(approved.state()).isEqualTo(InterventionState.SCHEDULING_PENDING);
        assertThat(f.stored().agreedProposalVersion()).contains(2);
        assertThat(f.ports.outbox.getLast().event()).isInstanceOfSatisfying(InterventionApproved.class,
                e -> assertThat(e.interventionState()).isEqualTo(InterventionState.SCHEDULING_PENDING));
    }

    @Test
    void step1OnlyTheAssignedContractorBeforeAnyField() {
        for (UserId other : List.of(ADMIN_A, RESIDENT_A1, new UserId("another-contractor"))) {
            assertThatThrownBy(() -> f.responses.respond(f.answer(other, "k1", 2, 1, "maybe", null, null)))
                    .as(other.value()).isInstanceOf(AccessDeniedException.class);
        }
        assertNothingChanged();
    }

    @Test
    void step1TheContractorSeesNothingBeforeTheRequestIsSent() {
        AgreementFixtures draft = new AgreementFixtures();

        assertThatThrownBy(() -> draft.responses.respond(draft.accept("k1", 1, 1)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void step2FieldsBeforeTheState() {
        f.responses.respond(f.accept("k0", 2, 1));

        assertThatThrownBy(() -> f.responses.respond(f.decline("k1", 9, 1, null, null)))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("reasonCode", "required")));
    }

    @Test
    void step3AnAnsweredRequestIs409AndAStaleVersionIsAConflict() {
        assertThatThrownBy(() -> f.responses.respond(f.accept("k1", 1, 1)))
                .isInstanceOfSatisfying(VersionConflictException.class,
                        e -> assertThat(e.currentVersion()).isEqualTo(2));
        assertNothingChanged();

        f.responses.respond(f.accept("k2", 2, 1));
        assertThatThrownBy(() -> f.responses.respond(f.decline("k3", 3, 1, "other", null)))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("scheduling_pending"));
    }

    @Test
    void step4AnswersNameTheApprovedVersion() {
        assertThatThrownBy(() -> f.responses.respond(f.accept("k1", 2, 7)))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("proposalVersion", "not_current")));
        assertNothingChanged();
    }

    @Test
    void step5AnIssueNoLongerDispatchableIs409() {
        f.ports.issues.put(f.issueId, issue(f.issueId, "cancelled", 5, false));

        assertThatThrownBy(() -> f.responses.respond(f.accept("k1", 2, 1)))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("cancelled"));
        assertNothingChanged();
    }

    @Test
    void anAnswerToAVersionTheAdministratorWithdrewIsRefused() {
        f.terms.changeTerms(f.changeTerms("t1", 2));

        assertThatThrownBy(() -> f.responses.respond(f.accept("k1", 3, 1)))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("awaiting_approval"));
        assertThatThrownBy(() -> f.responses.respond(f.accept("k2", 2, 1)))
                .as("with the version from before the change")
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void theSameKeyAndBodyReplayTheResult() {
        ResponseRecorded first = f.responses.respond(f.counterpropose("k1", 2, 1));
        f.clock.advance(Duration.ofMinutes(5));

        ResponseRecorded again = f.responses.respond(f.counterpropose("k1", 2, 1));

        assertThat(again).isEqualTo(new ResponseRecorded(first.interventionId(), first.state(), first.version(),
                first.proposalVersion(), true));
        assertThat(f.ports.proposals).hasSize(2);
        assertThat(f.ports.outbox).hasSize(2);
    }

    @Test
    void theSameKeyWithAnotherDecisionIsRefused() {
        f.responses.respond(f.accept("k1", 2, 1));

        assertThatThrownBy(() -> f.responses.respond(f.decline("k1", 2, 1, "other", null)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(f.stored().state()).isEqualTo(InterventionState.SCHEDULING_PENDING);
    }

    @Test
    void aReplayReChecksThatTheCallerIsStillTheAssignedContractor() {
        f.responses.respond(f.accept("k1", 2, 1));
        f.ports.contractors.put(PLUMBER.id(), new pt.isep.bmc.dispatch.domain.contractor.Contractor(PLUMBER.id(),
                Optional.of(new UserId("new-login")), PLUMBER.displayName(), PLUMBER.specialisations(),
                PLUMBER.coverage(), true, true));

        assertThatThrownBy(() -> f.responses.respond(f.accept("k1", 2, 1))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anOutboxFailureRollsBackTheAnswer() {
        InMemoryPorts ports = f.ports;
        var failing = new pt.isep.bmc.dispatch.application.command.RespondToRequestService(ports, ports, ports,
                ports, event -> {
                    throw new IllegalStateException("simulated outbox failure");
                }, ports, ports, ports, f.clock);

        assertThatThrownBy(() -> failing.respond(f.counterpropose("k1", 2, 1)))
                .isInstanceOf(IllegalStateException.class);
        assertNothingChanged();
    }
}
