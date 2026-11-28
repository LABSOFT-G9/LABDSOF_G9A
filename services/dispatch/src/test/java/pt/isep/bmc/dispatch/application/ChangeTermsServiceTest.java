package pt.isep.bmc.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.dispatch.application.ApplicationFixtures.*;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.application.command.ChangeTermsCommand;
import pt.isep.bmc.dispatch.application.command.TermsChanged;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.domain.event.InterventionTermsChanged;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.FieldViolation;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;

/** US-07: changed terms are a new version, withdraw an earlier approval and write InterventionTermsChanged. */
class ChangeTermsServiceTest {

    AgreementFixtures f = new AgreementFixtures();

    /** The service's time: the clock at millisecond precision. */
    Instant now() {
        return f.clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    @Test
    void newTermsOnADraftAreTheNextVersion() {
        TermsChanged result = f.terms.changeTerms(f.changeTerms("k1", 1));

        assertThat(result).isEqualTo(new TermsChanged(f.interventionId, InterventionState.AWAITING_APPROVAL, 2, 2,
                false));
        assertThat(f.ports.findProposal(f.interventionId, 2)).hasValueSatisfying(p -> {
            assertThat(p.authorRole()).isEqualTo("administrator");
            assertThat(p.createdBy()).isEqualTo(ADMIN_A);
            assertThat(p.interventionVersion()).isEqualTo(2);
            assertThat(p.terms()).isEqualTo("Inspect, repair and report the cost.");
        });
        assertThat(f.ports.outbox).singleElement().satisfies(e -> {
            assertThat(e.correlationId()).isEqualTo("c-terms");
            assertThat(e.event()).isEqualTo(new InterventionTermsChanged(f.interventionId.value(), 2, BUILDING_A,
                    now(), f.issueId, 2, 1, "administrator", InterventionState.AWAITING_APPROVAL,
                    Optional.empty(), InterventionState.AWAITING_APPROVAL));
        });
    }

    @Test
    void newTermsAfterSendingWithdrawTheApproval() {
        f.sent();

        f.terms.changeTerms(f.changeTerms("k1", 2));

        assertThat(f.stored().state()).isEqualTo(InterventionState.AWAITING_APPROVAL);
        assertThat(f.stored().approvedProposalVersion()).isEmpty();
        assertThat(f.stored().contractorContacted()).isTrue();
        assertThat(f.ports.outbox).hasSize(2);
        assertThat(f.ports.outbox.getLast().event()).as("the contractor is told the request is withdrawn")
                .isEqualTo(new InterventionTermsChanged(f.interventionId.value(), 3, BUILDING_A, now(),
                        f.issueId, 2, 1, "administrator", InterventionState.SENT, Optional.of(CONTRACTOR_LOGIN),
                        InterventionState.AWAITING_APPROVAL));
    }

    @Test
    void newTermsOverACounterproposalNameTheContractorWhoseVersionIsSuperseded() {
        f.sent();
        f.responses.respond(f.counterpropose("counter", 2, 1));

        TermsChanged result = f.terms.changeTerms(f.changeTerms("k1", 3));

        assertThat(result.proposalVersion()).isEqualTo(3);
        assertThat(f.ports.outbox.getLast().event()).isEqualTo(new InterventionTermsChanged(f.interventionId.value(),
                4, BUILDING_A, now(), f.issueId, 3, 2, "contractor", InterventionState.AWAITING_APPROVAL,
                Optional.of(CONTRACTOR_LOGIN), InterventionState.AWAITING_APPROVAL));
    }

    @Test
    void newTermsOverAnUnsentChangeDoNotNameTheContractorAgain() {
        f.sent();
        f.terms.changeTerms(f.changeTerms("k1", 2));

        f.terms.changeTerms(f.changeTerms("k2", 3));

        assertThat(f.ports.outbox.getLast().event()).isInstanceOfSatisfying(InterventionTermsChanged.class, e -> {
            assertThat(e.previousState()).isEqualTo(InterventionState.AWAITING_APPROVAL);
            assertThat(e.contractorUserId()).isEmpty();
        });
    }

    @Test
    void onlyAnAdministratorBeforeTheFieldsThenFieldsBeforeTheState() {
        ChangeTermsCommand invalid = new ChangeTermsCommand(RESIDENT_A1, new IdempotencyKey("k1"), f.interventionId,
                9, "short", null, null, "", "c1");
        assertThatThrownBy(() -> f.terms.changeTerms(invalid)).isInstanceOf(AccessDeniedException.class);

        f.sent();
        f.responses.respond(f.accept("accept", 2, 1));
        assertThatThrownBy(() -> f.terms.changeTerms(new ChangeTermsCommand(ADMIN_A, new IdempotencyKey("k2"),
                f.interventionId, 9, "short", null, null, "", "c2")))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .extracting(FieldViolation::field)
                        .containsExactlyInAnyOrder("scopeExtract", "window.start", "window.end", "terms"));
        assertThatThrownBy(() -> f.terms.changeTerms(f.changeTerms("k3", 9)))
                .as("agreed terms do not change")
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("scheduling_pending"));
    }

    @Test
    void theSameKeyAndBodyReplayTheNewVersion() {
        TermsChanged first = f.terms.changeTerms(f.changeTerms("k1", 1));
        f.clock.advance(Duration.ofMinutes(5));

        TermsChanged again = f.terms.changeTerms(f.changeTerms("k1", 1));

        assertThat(again).isEqualTo(new TermsChanged(first.interventionId(), first.state(), first.version(),
                first.proposalVersion(), true));
        assertThat(f.ports.proposals).hasSize(2);
        assertThat(f.ports.outbox).as("no second event").hasSize(1);
    }
}
