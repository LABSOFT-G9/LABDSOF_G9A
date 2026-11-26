package pt.isep.bmc.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.dispatch.domain.DomainFixtures.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.domain.contractor.Contractor;
import pt.isep.bmc.dispatch.domain.intervention.DraftOutcome;
import pt.isep.bmc.dispatch.domain.intervention.DraftTerms;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionDrafting;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.FieldViolation;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;
import pt.isep.bmc.dispatch.domain.shared.VersionConflictException;

/** US-06: the draft request's rules and the order of its checks. */
class InterventionDraftingTest {

    private static final String SCOPE = "  Leak above garage light fitting, level -1. Access via management.  ";
    private static final String START = "2026-11-27T09:00:00Z";
    private static final String END = "2026-11-27T12:00:00Z";
    private static final InterventionId NEW_ID = new InterventionId(UUID.randomUUID());
    private static final Contractor PLUMBER = contractor(1, "Plumbing Co.", "plumbing", true, BUILDING_A);

    private static DraftTerms terms() {
        return InterventionDrafting.validate(PLUMBER.id().toString(), SCOPE, START, END, " Inspect and repair. ", NOW);
    }

    @Test
    void validTermsAreTrimmedAndKeptInUtc() {
        DraftTerms terms = InterventionDrafting.validate(PLUMBER.id().toString(), SCOPE, "2026-11-27T10:00:00+01:00",
                END, " Inspect and repair. ", NOW);
        assertThat(terms.scopeExtract()).isEqualTo(SCOPE.strip());
        assertThat(terms.terms()).isEqualTo("Inspect and repair.");
        assertThat(terms.window().start()).isEqualTo(Instant.parse(START));
    }

    @Test
    void everyInvalidFieldIsReportedAtOnce() {
        assertThatThrownBy(() -> InterventionDrafting.validate("not-a-uuid", "too short", "2026-11-26T09:00:00Z",
                "2026-11-26T08:00:00Z", "", NOW))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations()).containsExactlyInAnyOrder(
                        new FieldViolation("contractorId", "invalid_format"),
                        new FieldViolation("scopeExtract", "too_short"),
                        new FieldViolation("terms", "required"),
                        new FieldViolation("window.start", "not_in_future"),
                        new FieldViolation("window.end", "not_after_start")));
    }

    @Test
    void lengthsAreCountedInCodePointsWithinTheStoryLimits() {
        String scope2000 = "🔧".repeat(2_000);
        assertThat(InterventionDrafting.validate(PLUMBER.id().toString(), scope2000, START, END, "x", NOW)
                .scopeExtract()).isEqualTo(scope2000);
        assertThatThrownBy(() -> InterventionDrafting.validate(PLUMBER.id().toString(), "x".repeat(2_001), START, END,
                "y".repeat(1_001), NOW))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations()).containsExactlyInAnyOrder(
                        new FieldViolation("scopeExtract", "too_long"), new FieldViolation("terms", "too_long")));
        assertThatThrownBy(() -> InterventionDrafting.validate(PLUMBER.id().toString(), "Bell \u0007 rings in the hall",
                START, END, "ok", NOW))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("scopeExtract", "invalid_characters")));
    }

    @Test
    void missingOrMalformedTimesAndContractor() {
        assertThatThrownBy(() -> InterventionDrafting.validate(null, SCOPE, null, "tomorrow", "ok", NOW))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations()).containsExactlyInAnyOrder(
                        new FieldViolation("contractorId", "required"),
                        new FieldViolation("window.start", "required"),
                        new FieldViolation("window.end", "invalid_format")));
    }

    @Test
    void aDraftIsAwaitingApprovalVersionOneWithProposalOneAndTheProjectionSnapshot() {
        DraftOutcome outcome = InterventionDrafting.draft(administratorOf(ADMIN_A, BUILDING_A), dispatchablePlumbing(),
                Optional.empty(), terms(), Optional.of(PLUMBER), NEW_ID, NOW);

        Intervention draft = outcome.intervention();
        assertThat(draft.state()).isEqualTo(InterventionState.AWAITING_APPROVAL);
        assertThat(draft.version()).isEqualTo(1);
        assertThat(draft.currentProposalVersion()).isEqualTo(1);
        assertThat(draft.buildingId()).isEqualTo(BUILDING_A);
        assertThat(draft.projectedIssueVersion()).isEqualTo(3);
        assertThat(draft.contractorId()).isEqualTo(PLUMBER.id());
        assertThat(outcome.proposal().number()).isEqualTo(1);
        assertThat(outcome.proposal().authorRole()).isEqualTo("administrator");
        assertThat(outcome.proposal().createdBy()).isEqualTo(ADMIN_A);
    }

    @Test
    void onlyAnAdministratorOfTheBuildingMayDraftAndOthersGet404() {
        assertThatThrownBy(() -> InterventionDrafting.draft(nobody(ADMIN_A), dispatchablePlumbing(), Optional.empty(),
                terms(), Optional.of(PLUMBER), NEW_ID, NOW)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> InterventionDrafting.draft(administratorOf(ADMIN_A, BUILDING_B),
                dispatchablePlumbing(), Optional.empty(), terms(), Optional.of(PLUMBER), NEW_ID, NOW))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anIssueThatIsNotReadyOrAlreadyHasAnActiveInterventionIsRefusedWith409() {
        assertThatThrownBy(() -> InterventionDrafting.draft(administratorOf(ADMIN_A, BUILDING_A),
                issue("in_triage", false, "plumbing"), Optional.empty(), terms(), Optional.of(PLUMBER), NEW_ID, NOW))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("in_triage"));

        InterventionId active = new InterventionId(UUID.randomUUID());
        assertThatThrownBy(() -> InterventionDrafting.draft(administratorOf(ADMIN_A, BUILDING_A),
                issue("in_coordination", true, "plumbing"), Optional.of(active), terms(), Optional.of(PLUMBER), NEW_ID,
                NOW))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.activeInterventionId()).contains(active.value()));
    }

    @Test
    void anUnknownAndAnIneligibleContractorAnswerTheSame() {
        for (Optional<Contractor> contractor : java.util.List.of(Optional.<Contractor>empty(),
                Optional.of(contractor(1, "Plumbing Co.", "plumbing", false, BUILDING_A)))) {
            assertThatThrownBy(() -> InterventionDrafting.draft(administratorOf(ADMIN_A, BUILDING_A),
                    dispatchablePlumbing(), Optional.empty(), terms(), contractor, NEW_ID, NOW))
                    .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                            .containsExactly(new FieldViolation("contractorId", "not_eligible")));
        }
    }

    @Test
    void replacingTheContractorMovesTheVersionAndRechecksEligibility() {
        Intervention draft = draft();
        Contractor other = contractor(2, "Other Plumbing", "plumbing", true, BUILDING_A);

        Intervention replaced = InterventionDrafting.replaceContractor(administratorOf(ADMIN_A, BUILDING_A), draft,
                dispatchablePlumbing(), 1, other.id(), Optional.of(other), NOW.plusSeconds(5));

        assertThat(replaced.contractorId()).isEqualTo(other.id());
        assertThat(replaced.version()).isEqualTo(2);
        assertThat(replaced.updatedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(replaced.createdAt()).isEqualTo(draft.createdAt());

        Contractor electrician = contractor(3, "Electric", "electrical", true, BUILDING_A);
        assertThatThrownBy(() -> InterventionDrafting.replaceContractor(administratorOf(ADMIN_A, BUILDING_A), draft,
                dispatchablePlumbing(), 1, electrician.id(), Optional.of(electrician), NOW))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aStaleVersionIsAConflictButALaterStateIsAnInvalidTransitionFirst() {
        Intervention draft = draft();
        assertThatThrownBy(() -> InterventionDrafting.replaceContractor(administratorOf(ADMIN_A, BUILDING_A), draft,
                dispatchablePlumbing(), 7, PLUMBER.id(), Optional.of(PLUMBER), NOW))
                .isInstanceOfSatisfying(VersionConflictException.class, e -> assertThat(e.currentVersion()).isEqualTo(1));

        Intervention sent = new Intervention(draft.id(), draft.issueId(), draft.buildingId(), draft.contractorId(),
                InterventionState.SENT, 2, 1, 3, NOW, ADMIN_A, NOW, NOW);
        assertThatThrownBy(() -> InterventionDrafting.replaceContractor(administratorOf(ADMIN_A, BUILDING_A), sent,
                dispatchablePlumbing(), 1, PLUMBER.id(), Optional.of(PLUMBER), NOW))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("sent"));
    }

    @Test
    void replacingNeedsAnAdministratorOfTheInterventionsBuilding() {
        assertThatThrownBy(() -> InterventionDrafting.replaceContractor(administratorOf(ADMIN_A, BUILDING_B), draft(),
                dispatchablePlumbing(), 1, PLUMBER.id(), Optional.of(PLUMBER), NOW))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void terminalStatesAreDeclinedCancelledAndClosed() {
        assertThat(java.util.Arrays.stream(InterventionState.values()).filter(InterventionState::isTerminal))
                .containsExactlyInAnyOrder(InterventionState.DECLINED, InterventionState.CANCELLED,
                        InterventionState.CLOSED);
    }

    private static Intervention draft() {
        return InterventionDrafting.draft(administratorOf(ADMIN_A, BUILDING_A), dispatchablePlumbing(),
                Optional.empty(), terms(), Optional.of(PLUMBER), NEW_ID, NOW).intervention();
    }
}
