package pt.isep.bmc.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.dispatch.domain.DomainFixtures.*;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.domain.contractor.Contractor;
import pt.isep.bmc.dispatch.domain.intervention.DeclineReason;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionAgreement;
import pt.isep.bmc.dispatch.domain.intervention.InterventionAgreement.ApprovalOutcome;
import pt.isep.bmc.dispatch.domain.intervention.InterventionAgreement.ResponseOutcome;
import pt.isep.bmc.dispatch.domain.intervention.InterventionAgreement.ResponseRequest;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.intervention.Money;
import pt.isep.bmc.dispatch.domain.intervention.ProposalVersion;
import pt.isep.bmc.dispatch.domain.intervention.ResponseDecision;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.BusinessRuleException;
import pt.isep.bmc.dispatch.domain.shared.FieldViolation;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.UserId;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;
import pt.isep.bmc.dispatch.domain.shared.VersionConflictException;

/** US-07: approval, the contractor's answer, changed terms and the order of their checks. */
class InterventionAgreementTest {

    static final UserId CONTRACTOR_LOGIN = new UserId("0199f2a0-0000-7000-8000-000000000301");
    static final Contractor PLUMBER = new Contractor(contractorId(1), Optional.of(CONTRACTOR_LOGIN), "Plumbing Co.",
            Set.of("plumbing"), Set.of(BUILDING_A), true, true);
    static final Contractor NO_LOGIN = contractor(2, "Plumbing Twin", "plumbing", true, BUILDING_A);
    static final InterventionId ID = new InterventionId(UUID.randomUUID());
    static final TimeWindow WINDOW = new TimeWindow(NOW.plusSeconds(86_400), NOW.plusSeconds(97_200));
    static final String START = "2026-11-28T09:00:00Z";
    static final String END = "2026-11-28T12:00:00Z";

    static Intervention draft(Contractor contractor) {
        return Intervention.draft(ID, ISSUE, BUILDING_A, contractor.id(), 3, NOW, ADMIN_A, NOW);
    }

    static ProposalVersion firstProposal() {
        return ProposalVersion.proposed(ID, 1, ProposalVersion.AUTHOR_ADMINISTRATOR, "Leak above the garage light.",
                WINDOW, "Inspect and repair.", Optional.empty(), 1, ADMIN_A, NOW);
    }

    static ApprovalOutcome approve(Intervention intervention, ProposalVersion proposal, Contractor contractor) {
        return InterventionAgreement.approve(administratorOf(ADMIN_A, BUILDING_A), intervention,
                dispatchablePlumbing(), proposal, Optional.of(contractor), intervention.version(),
                proposal.number(), NOW);
    }

    static Intervention sent() {
        return approve(draft(PLUMBER), firstProposal(), PLUMBER).intervention();
    }

    static ResponseRequest answer(String decision, Integer version) {
        return InterventionAgreement.validateResponse(version, decision, "unavailable", null, START, END,
                "Can come on Friday instead.", null, null, NOW);
    }

    static ResponseOutcome respond(Intervention intervention, ResponseRequest request) {
        return InterventionAgreement.respond(CONTRACTOR_LOGIN, intervention, dispatchablePlumbing(), firstProposal(),
                request, intervention.version(), NOW);
    }

    // Approval

    @Test
    void approvingTheAdministratorsVersionSendsTheRequest() {
        ApprovalOutcome outcome = approve(draft(PLUMBER), firstProposal(), PLUMBER);

        Intervention sent = outcome.intervention();
        assertThat(sent.state()).isEqualTo(InterventionState.SENT);
        assertThat(sent.version()).isEqualTo(2);
        assertThat(sent.approvedProposalVersion()).contains(1);
        assertThat(sent.agreedProposalVersion()).as("approval is not acceptance").isEmpty();
        assertThat(sent.contractorContacted()).isTrue();
        assertThat(outcome.approvedProposal().approval()).hasValueSatisfying(approval -> {
            assertThat(approval.approvedBy()).isEqualTo(ADMIN_A);
            assertThat(approval.contractorId()).isEqualTo(PLUMBER.id());
            assertThat(approval.interventionVersion()).isEqualTo(2);
        });
        assertThat(outcome.contractorUserId()).isEqualTo(CONTRACTOR_LOGIN);
    }

    @Test
    void approvingAContractorsCounterproposalAgreesTheTermsAtOnce() {
        ResponseOutcome counter = respond(sent(), answer("counterpropose", 1));
        ProposalVersion second = counter.counterproposal().orElseThrow();

        ApprovalOutcome outcome = approve(counter.intervention(), second, PLUMBER);

        assertThat(outcome.intervention().state()).isEqualTo(InterventionState.SCHEDULING_PENDING);
        assertThat(outcome.intervention().agreedProposalVersion()).contains(2);
    }

    @Test
    void approvalChecksTheStateBeforeTheVersionAndTheVersionBeforeTheNamedProposal() {
        Intervention sent = sent();
        assertThatThrownBy(() -> InterventionAgreement.approve(administratorOf(ADMIN_A, BUILDING_A), sent,
                dispatchablePlumbing(), firstProposal(), Optional.of(PLUMBER), 9, 9, NOW))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("sent"));

        Intervention draft = draft(PLUMBER);
        assertThatThrownBy(() -> InterventionAgreement.approve(administratorOf(ADMIN_A, BUILDING_A), draft,
                issue("cancelled", false, "plumbing"), firstProposal(), Optional.of(NO_LOGIN), 9, 9, NOW))
                .isInstanceOfSatisfying(VersionConflictException.class,
                        e -> assertThat(e.currentVersion()).isEqualTo(1));
        assertThatThrownBy(() -> InterventionAgreement.approve(administratorOf(ADMIN_A, BUILDING_A), draft,
                issue("cancelled", false, "plumbing"), firstProposal(), Optional.of(NO_LOGIN), 1, 2, NOW))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("proposalVersion", "not_current")));
    }

    @Test
    void approvalThenChecksTheIssueThenEligibilityThenTheContractorsLogin() {
        Intervention draft = draft(NO_LOGIN);
        Contractor inactive = new Contractor(NO_LOGIN.id(), Optional.empty(), "Gone", Set.of("plumbing"),
                Set.of(BUILDING_A), false, true);
        assertThatThrownBy(() -> InterventionAgreement.approve(administratorOf(ADMIN_A, BUILDING_A), draft,
                issue("cancelled", false, "plumbing"), firstProposal(), Optional.of(inactive), 1, 1, NOW))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("cancelled"));
        assertThatThrownBy(() -> approve(draft, firstProposal(), inactive))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("CONTRACTOR_NOT_ELIGIBLE"));
        assertThatThrownBy(() -> approve(draft, firstProposal(), NO_LOGIN))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("CONTRACTOR_HAS_NO_ACCOUNT"));
    }

    @Test
    void onlyAnAdministratorOfTheBuildingApproves() {
        assertThatThrownBy(() -> InterventionAgreement.approve(administratorOf(ADMIN_A, BUILDING_B), draft(PLUMBER),
                dispatchablePlumbing(), firstProposal(), Optional.of(PLUMBER), 1, 1, NOW))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aSentRequestKeepsItsContractor() {
        Intervention counter = respond(sent(), answer("counterpropose", 1)).intervention();

        assertThat(counter.state()).isEqualTo(InterventionState.AWAITING_APPROVAL);
        assertThatThrownBy(() -> counter.replaceContractor(counter.version(), contractorId(3), NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("CONTRACTOR_ALREADY_CONTACTED"));
    }

    // The contractor's answer

    @Test
    void onlyTheContractorWhoseLoginItIsAnswersAndOnlyOnceSent() {
        InterventionAgreement.requireAssignedContractor(CONTRACTOR_LOGIN, sent(), Optional.of(PLUMBER));

        assertThatThrownBy(() -> InterventionAgreement.requireAssignedContractor(CONTRACTOR_LOGIN, draft(PLUMBER),
                Optional.of(PLUMBER))).as("before anything was sent").isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> InterventionAgreement.requireAssignedContractor(new UserId("other"), sent(),
                Optional.of(PLUMBER))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> InterventionAgreement.requireAssignedContractor(ADMIN_A, sent(),
                Optional.of(PLUMBER))).as("the administrator").isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void acceptanceAgreesTheApprovedVersion() {
        ResponseOutcome outcome = respond(sent(), answer("accept", 1));

        assertThat(outcome.intervention().state()).isEqualTo(InterventionState.SCHEDULING_PENDING);
        assertThat(outcome.intervention().agreedProposalVersion()).contains(1);
        assertThat(outcome.response().decision()).isEqualTo(ResponseDecision.ACCEPT);
        assertThat(outcome.response().reason()).isEmpty();
        assertThat(outcome.counterproposal()).isEmpty();
    }

    @Test
    void aDeclineIsTerminalWithItsReason() {
        ResponseRequest request = InterventionAgreement.validateResponse(1, "decline", "outside_expertise",
                "  Not our kind of job.  ", null, null, null, null, null, NOW);

        ResponseOutcome outcome = respond(sent(), request);

        assertThat(outcome.intervention().state()).isEqualTo(InterventionState.DECLINED);
        assertThat(outcome.intervention().state().isTerminal()).isTrue();
        assertThat(outcome.response().reason()).contains(DeclineReason.OUTSIDE_EXPERTISE);
        assertThat(outcome.response().note()).contains("Not our kind of job.");
    }

    @Test
    void aCounterproposalIsANewContractorVersionKeepingTheAdministratorsScope() {
        ResponseRequest request = InterventionAgreement.validateResponse(1, "counterpropose", null, null, START, END,
                "Friday morning; parts extra.", "85.5", "EUR", NOW);

        ResponseOutcome outcome = respond(sent(), request);

        Intervention next = outcome.intervention();
        assertThat(next.state()).isEqualTo(InterventionState.AWAITING_APPROVAL);
        assertThat(next.currentProposalVersion()).isEqualTo(2);
        assertThat(next.approvedProposalVersion()).isEmpty();
        assertThat(outcome.counterproposal()).hasValueSatisfying(p -> {
            assertThat(p.number()).isEqualTo(2);
            assertThat(p.byContractor()).isTrue();
            assertThat(p.scopeExtract()).isEqualTo(firstProposal().scopeExtract());
            assertThat(p.terms()).isEqualTo("Friday morning; parts extra.");
            assertThat(p.costEstimate()).contains(new Money(new BigDecimal("85.50"), "EUR"));
            assertThat(p.interventionVersion()).isEqualTo(next.version());
        });
        assertThat(outcome.response().counterProposalVersion()).contains(2);
    }

    @Test
    void anAnswerChecksTheStateThenTheVersionThenTheNamedVersionThenTheIssue() {
        assertThatThrownBy(() -> InterventionAgreement.respond(CONTRACTOR_LOGIN, draft(PLUMBER),
                issue("cancelled", false, "plumbing"), firstProposal(), answer("accept", 9), 9, NOW))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("awaiting_approval"));
        assertThatThrownBy(() -> InterventionAgreement.respond(CONTRACTOR_LOGIN, sent(),
                issue("cancelled", false, "plumbing"), firstProposal(), answer("accept", 9), 9, NOW))
                .isInstanceOfSatisfying(VersionConflictException.class,
                        e -> assertThat(e.currentVersion()).isEqualTo(2));
        assertThatThrownBy(() -> InterventionAgreement.respond(CONTRACTOR_LOGIN, sent(),
                issue("cancelled", false, "plumbing"), firstProposal(), answer("accept", 9), 2, NOW))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("proposalVersion", "not_current")));
        assertThatThrownBy(() -> InterventionAgreement.respond(CONTRACTOR_LOGIN, sent(),
                issue("cancelled", false, "plumbing"), firstProposal(), answer("accept", 1), 2, NOW))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).as("the issue's state").isEqualTo("cancelled"));
    }

    @Test
    void everyInvalidFieldOfAnAnswerIsReportedAtOnce() {
        assertThatThrownBy(() -> InterventionAgreement.validateResponse(null, "maybe", null, null, null, null, null,
                null, null, NOW))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactlyInAnyOrder(new FieldViolation("proposalVersion", "required"),
                                new FieldViolation("decision", "invalid")));
        assertThatThrownBy(() -> InterventionAgreement.validateResponse(0, "decline", "busy", "x".repeat(501), null,
                null, null, null, null, NOW))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactlyInAnyOrder(new FieldViolation("proposalVersion", "invalid"),
                                new FieldViolation("reasonCode", "invalid"), new FieldViolation("note", "too_long")));
        assertThatThrownBy(() -> InterventionAgreement.validateResponse(1, "counterpropose", null, null,
                "2026-11-25T09:00:00Z", null, " ", "-3", "USD", NOW))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactlyInAnyOrder(new FieldViolation("terms", "required"),
                                new FieldViolation("window.end", "required"),
                                new FieldViolation("window.start", "not_in_future"),
                                new FieldViolation("proposedCost.amount", "invalid_format"),
                                new FieldViolation("proposedCost.currency", "unsupported")));
        assertThatThrownBy(() -> InterventionAgreement.validateResponse(null, null, null, null, null, null, null,
                null, null, NOW))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactlyInAnyOrder(new FieldViolation("proposalVersion", "required"),
                                new FieldViolation("decision", "required")));
    }

    @Test
    void costIsOptionalButBothPartsGoTogetherAndTheAmountIsPositive() {
        assertThat(InterventionAgreement.validateResponse(1, "counterpropose", null, null, START, END, "Friday.",
                null, null, NOW).counter().orElseThrow().costEstimate()).isEmpty();
        assertThatThrownBy(() -> InterventionAgreement.validateResponse(1, "counterpropose", null, null, START, END,
                "Friday.", "0.00", null, NOW))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactlyInAnyOrder(new FieldViolation("proposedCost.amount", "not_positive"),
                                new FieldViolation("proposedCost.currency", "required")));
        assertThatThrownBy(() -> InterventionAgreement.validateResponse(1, "counterpropose", null, null, START, END,
                "Friday.", "12345678", "EUR", NOW))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("proposedCost.amount", "invalid_format")));
    }

    @Test
    void fieldsThatDoNotBelongToTheDecisionAreIgnored() {
        ResponseRequest accept = InterventionAgreement.validateResponse(1, "accept", "busy", "x".repeat(900),
                "garbage", "garbage", "", "-1", "XYZ", NOW);

        assertThat(accept.reason()).isEmpty();
        assertThat(accept.note()).isEmpty();
        assertThat(accept.counter()).isEmpty();
    }

    // Changed terms

    @Test
    void changedTermsBeforeTheAnswerWithdrawTheApproval() {
        var terms = InterventionAgreement.validateTerms("Leak above the garage light, level -1.", START, END,
                "Inspect, repair and report.", NOW);

        var outcome = InterventionAgreement.changeTerms(administratorOf(ADMIN_A, BUILDING_A), sent(),
                dispatchablePlumbing(), terms, 2, NOW);

        assertThat(outcome.intervention().state()).isEqualTo(InterventionState.AWAITING_APPROVAL);
        assertThat(outcome.intervention().approvedProposalVersion()).isEmpty();
        assertThat(outcome.intervention().currentProposalVersion()).isEqualTo(2);
        assertThat(outcome.proposal().authorRole()).isEqualTo(ProposalVersion.AUTHOR_ADMINISTRATOR);
        assertThat(outcome.proposal().approval()).isEmpty();
        assertThatThrownBy(() -> outcome.intervention().accept(3, 1, NOW))
                .as("the contractor can no longer answer the withdrawn version")
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("awaiting_approval"));
    }

    @Test
    void termsCannotChangeOnceAgreed() {
        Intervention accepted = respond(sent(), answer("accept", 1)).intervention();
        var terms = InterventionAgreement.validateTerms("Leak above the garage light, level -1.", START, END,
                "Inspect, repair and report.", NOW);

        assertThatThrownBy(() -> InterventionAgreement.changeTerms(administratorOf(ADMIN_A, BUILDING_A), accepted,
                dispatchablePlumbing(), terms, accepted.version(), NOW))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("scheduling_pending"));
    }
}
