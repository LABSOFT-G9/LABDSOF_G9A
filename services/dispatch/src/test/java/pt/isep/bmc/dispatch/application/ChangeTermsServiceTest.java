package pt.isep.bmc.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.dispatch.application.ApplicationFixtures.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.application.command.ChangeTermsCommand;
import pt.isep.bmc.dispatch.application.command.TermsChanged;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.FieldViolation;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;

/** US-07: changed terms are a new version and withdraw an earlier approval; no event. */
class ChangeTermsServiceTest {

    AgreementFixtures f = new AgreementFixtures();

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
        assertThat(f.ports.outbox).isEmpty();
    }

    @Test
    void newTermsAfterSendingWithdrawTheApproval() {
        f.sent();

        f.terms.changeTerms(f.changeTerms("k1", 2));

        assertThat(f.stored().state()).isEqualTo(InterventionState.AWAITING_APPROVAL);
        assertThat(f.stored().approvedProposalVersion()).isEmpty();
        assertThat(f.stored().contractorContacted()).isTrue();
        assertThat(f.ports.outbox).as("only the approval's event").hasSize(1);
    }

    @Test
    void onlyAnAdministratorBeforeTheFieldsThenFieldsBeforeTheState() {
        ChangeTermsCommand invalid = new ChangeTermsCommand(RESIDENT_A1, new IdempotencyKey("k1"), f.interventionId,
                9, "short", null, null, "");
        assertThatThrownBy(() -> f.terms.changeTerms(invalid)).isInstanceOf(AccessDeniedException.class);

        f.sent();
        f.responses.respond(f.accept("accept", 2, 1));
        assertThatThrownBy(() -> f.terms.changeTerms(new ChangeTermsCommand(ADMIN_A, new IdempotencyKey("k2"),
                f.interventionId, 9, "short", null, null, "")))
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
    }
}
