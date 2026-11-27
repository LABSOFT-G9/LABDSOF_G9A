package pt.isep.bmc.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.domain.DomainFixtures.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.domain.intervention.CancelReason;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;

/** US-09, technical design 3.2: how Issue's closure ends an intervention in each state. */
class ExecutionRulesTest {

    static Intervention in(InterventionState state) {
        Intervention draft = Intervention.draft(new InterventionId(UUID.randomUUID()), ISSUE, BUILDING_A, contractorId(1),
                3, NOW, ADMIN_A, NOW);
        return new Intervention(draft.id(), draft.issueId(), draft.buildingId(), draft.contractorId(), state, 5, 1,
                Optional.of(1), Optional.of(1), true, Optional.empty(), Optional.empty(), 3, NOW, ADMIN_A, NOW, NOW);
    }

    static Optional<InterventionState> after(InterventionState state, boolean bookingNeedsCalendar) {
        return in(state).endedByIssue(CancelReason.ISSUE_RESOLVED, bookingNeedsCalendar, NOW).map(Intervention::state);
    }

    @Test
    void issuesClosureClosesAReportedCompletionAndCancelsEverythingEarlier() {
        assertThat(after(InterventionState.COMPLETION_REPORTED, false)).contains(InterventionState.CLOSED);
        assertThat(after(InterventionState.AWAITING_APPROVAL, true)).contains(InterventionState.CANCELLED);
        assertThat(after(InterventionState.SENT, false)).contains(InterventionState.CANCELLED);
        assertThat(after(InterventionState.BOOKED, true)).contains(InterventionState.CANCELLATION_PENDING);
        assertThat(after(InterventionState.IN_PROGRESS, true)).contains(InterventionState.CANCELLATION_PENDING);
        assertThat(after(InterventionState.SCHEDULING_PENDING, false)).contains(InterventionState.CANCELLED);
        for (InterventionState unchanged : new InterventionState[] {InterventionState.CANCELLATION_PENDING,
                InterventionState.DECLINED, InterventionState.CANCELLED, InterventionState.CLOSED}) {
            assertThat(after(unchanged, true)).as(unchanged.value()).isEmpty();
        }
    }

    @Test
    void aClosedInterventionRecordsNoCancellationReason() {
        assertThat(in(InterventionState.COMPLETION_REPORTED)
                .endedByIssue(CancelReason.ISSUE_RESOLVED, false, NOW).orElseThrow().cancelReason()).isEmpty();
        assertThat(in(InterventionState.SENT)
                .endedByIssue(CancelReason.ISSUE_CANCELLED, false, NOW).orElseThrow().cancelReason())
                .contains(CancelReason.ISSUE_CANCELLED);
    }

    @Test
    void anAdministratorCannotGiveTheCodesOfIssuesEvents() {
        assertThat(CancelReason.byAdministrator("issue_resolved")).isEmpty();
        assertThat(CancelReason.byAdministrator("issue_cancelled")).isEmpty();
        assertThat(CancelReason.byAdministrator("rescheduling")).contains(CancelReason.RESCHEDULING);
    }
}
