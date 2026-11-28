package pt.isep.bmc.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.dispatch.application.ApplicationFixtures.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.application.command.ProposalApproved;
import pt.isep.bmc.dispatch.application.error.IdempotencyKeyReusedException;
import pt.isep.bmc.dispatch.domain.event.InterventionApproved;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.BusinessRuleException;
import pt.isep.bmc.dispatch.domain.shared.FieldViolation;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.UserId;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;
import pt.isep.bmc.dispatch.domain.shared.VersionConflictException;

/** US-07: approving a proposal version sends the request once, with its event. */
class ApproveProposalServiceTest {

    AgreementFixtures f = new AgreementFixtures();

    void assertNothingChanged() {
        assertThat(f.stored().version()).isEqualTo(1);
        assertThat(f.stored().state()).isEqualTo(InterventionState.AWAITING_APPROVAL);
        assertThat(f.ports.proposals).allSatisfy(p -> assertThat(p.approval()).isEmpty());
        assertThat(f.ports.outbox).isEmpty();
        assertThat(f.ports.keys).as("a rejected request leaves no key").isEmpty();
    }

    @Test
    void approvalSendsTheRequestAndWritesInterventionApprovedInTheSameTransaction() {
        ProposalApproved result = f.approval.approve(f.approve(ADMIN_A, "k1", 1, 1));

        assertThat(result).isEqualTo(new ProposalApproved(f.interventionId, InterventionState.SENT, 2, 1, false));
        assertThat(f.stored().contractorContacted()).isTrue();
        assertThat(f.ports.findProposal(f.interventionId, 1).orElseThrow().approval()).hasValueSatisfying(a -> {
            assertThat(a.approvedBy()).isEqualTo(ADMIN_A);
            assertThat(a.contractorId()).isEqualTo(PLUMBER.id());
            assertThat(a.approvedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)).truncatedTo(
                    java.time.temporal.ChronoUnit.MILLIS));
        });
        assertThat(f.ports.outbox).singleElement().satisfies(row -> {
            assertThat(row.correlationId()).isEqualTo("c-approve");
            assertThat(row.event()).isInstanceOfSatisfying(InterventionApproved.class, e -> {
                assertThat(e.aggregateId()).isEqualTo(f.interventionId.value());
                assertThat(e.aggregateVersion()).isEqualTo(2);
                assertThat(e.buildingId()).isEqualTo(BUILDING_A);
                assertThat(e.issueId()).isEqualTo(f.issueId);
                assertThat(e.contractorUserId()).isEqualTo(CONTRACTOR_LOGIN);
                assertThat(e.contractorDisplayName()).isEqualTo(PLUMBER.displayName());
                assertThat(e.approvedProposalVersion()).isEqualTo(1);
                assertThat(e.interventionState()).isEqualTo(InterventionState.SENT);
            });
        });
    }

    @Test
    void step1OnlyAnAdministratorOfTheBuildingBeforeAnyField() {
        for (UserId other : List.of(RESIDENT_A1, ADMIN_B, CONTRACTOR_LOGIN)) {
            assertThatThrownBy(() -> f.approval.approve(f.approve(other, "k1", 1, null))).as(other.value())
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertNothingChanged();
    }

    @Test
    void step2TheProposalVersionIsRequiredBeforeTheState() {
        f.sent();
        f.ports.keys.clear();
        f.ports.outbox.clear();

        assertThatThrownBy(() -> f.approval.approve(f.approve(ADMIN_A, "k1", 9, null)))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("proposalVersion", "required")));
    }

    @Test
    void step3AnApprovedRequestIs409BeforeTheVersion() {
        f.sent();

        assertThatThrownBy(() -> f.approval.approve(f.approve(ADMIN_A, "k2", 9, 1)))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("sent"));
        assertThat(f.ports.outbox).as("one request sent").hasSize(1);
    }

    @Test
    void step3AStaleVersionIs409WithTheCurrentOne() {
        assertThatThrownBy(() -> f.approval.approve(f.approve(ADMIN_A, "k1", 7, 1)))
                .isInstanceOfSatisfying(VersionConflictException.class,
                        e -> assertThat(e.currentVersion()).isEqualTo(1));
        assertNothingChanged();
    }

    @Test
    void step4AnotherProposalVersionIsNotCurrent() {
        assertThatThrownBy(() -> f.approval.approve(f.approve(ADMIN_A, "k1", 1, 2)))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("proposalVersion", "not_current")));
        assertNothingChanged();
    }

    @Test
    void step5AnIssueNoLongerDispatchableIs409WithItsState() {
        f.ports.issues.put(f.issueId, issue(f.issueId, "cancelled", 4, false));

        assertThatThrownBy(() -> f.approval.approve(f.approve(ADMIN_A, "k1", 1, 1)))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("cancelled"));
        assertNothingChanged();
    }

    @Test
    void step6AContractorNoLongerEligibleOrWithoutALoginIs422() {
        f.ports.contractors.put(PLUMBER.id(), contractor(1, "Synthetic Plumbing Co.", "plumbing", false, BUILDING_A));
        assertThatThrownBy(() -> f.approval.approve(f.approve(ADMIN_A, "k1", 1, 1)))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("CONTRACTOR_NOT_ELIGIBLE"));
        assertNothingChanged();

        AgreementFixtures noLogin = new AgreementFixtures(OTHER_PLUMBER);
        assertThatThrownBy(() -> noLogin.approval.approve(noLogin.approve(ADMIN_A, "k1", 1, 1)))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("CONTRACTOR_HAS_NO_ACCOUNT"));
        assertThat(noLogin.ports.outbox).isEmpty();
    }

    @Test
    void theSameKeyAndBodyReplayTheResultAndSendNothingAgain() {
        ProposalApproved first = f.approval.approve(f.approve(ADMIN_A, "k1", 1, 1));
        f.clock.advance(Duration.ofMinutes(5));

        ProposalApproved again = f.approval.approve(f.approve(ADMIN_A, "k1", 1, 1));

        assertThat(again).isEqualTo(new ProposalApproved(first.interventionId(), first.state(), first.version(),
                first.approvedProposalVersion(), true));
        assertThat(f.ports.outbox).hasSize(1);
    }

    @Test
    void aReplayReChecksTheCallersAccess() {
        f.approval.approve(f.approve(ADMIN_A, "k1", 1, 1));
        f.ports.memberships.put(MEMBERSHIP_ADMIN_A,
                membership(MEMBERSHIP_ADMIN_A, ADMIN_A, BUILDING_A, "administrator", false, 2));

        assertThatThrownBy(() -> f.approval.approve(f.approve(ADMIN_A, "k1", 1, 1)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theSameKeyWithAnotherBodyIsRefused() {
        f.approval.approve(f.approve(ADMIN_A, "k1", 1, 1));

        assertThatThrownBy(() -> f.approval.approve(f.approve(ADMIN_A, "k1", 2, 1)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(f.ports.outbox).hasSize(1);
    }

    @Test
    void aConcurrentRetryRollsBackItsEventAndReplaysTheWinner() {
        ProposalApproved[] winner = new ProposalApproved[1];
        f.ports.beforeNextRecord = () -> winner[0] = f.approval.approve(f.approve(ADMIN_A, "k1", 1, 1));

        ProposalApproved loser = f.approval.approve(f.approve(ADMIN_A, "k1", 1, 1));

        assertThat(loser.replayed()).isTrue();
        assertThat(loser.version()).isEqualTo(winner[0].version());
        assertThat(f.ports.outbox).as("one request sent").hasSize(1);
    }

    @Test
    void anOutboxFailureRollsBackTheApproval() {
        InMemoryPorts ports = f.ports;
        var failing = new pt.isep.bmc.dispatch.application.command.ApproveProposalService(ports, ports, ports, ports,
                ports, event -> {
                    throw new IllegalStateException("simulated outbox failure");
                }, ports, ports, ports, f.clock);

        assertThatThrownBy(() -> failing.approve(f.approve(ADMIN_A, "k1", 1, 1)))
                .isInstanceOf(IllegalStateException.class);
        assertNothingChanged();
    }
}
