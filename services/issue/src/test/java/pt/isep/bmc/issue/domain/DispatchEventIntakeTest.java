package pt.isep.bmc.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.dispatch.DispatchEvent;
import pt.isep.bmc.issue.domain.dispatch.DispatchEventIntake;
import pt.isep.bmc.issue.domain.dispatch.DispatchEventRecord;
import pt.isep.bmc.issue.domain.dispatch.DispatchEventRecord.Outcome;
import pt.isep.bmc.issue.domain.dispatch.DispatchEventType;
import pt.isep.bmc.issue.domain.dispatch.DispatchOutcome;
import pt.isep.bmc.issue.domain.dispatch.InterventionProjection;
import pt.isep.bmc.issue.domain.dispatch.InterventionState;
import pt.isep.bmc.issue.domain.dispatch.VisitWindow;
import pt.isep.bmc.issue.domain.event.IssueStateChanged;
import pt.isep.bmc.issue.domain.issue.CriticalCheckStatus;
import pt.isep.bmc.issue.domain.issue.HistoryEntryType;
import pt.isep.bmc.issue.domain.issue.HistoryVisibility;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;

/**
 * US-07-03: what a Dispatch event does to an issue (technical design 3.3).
 * Events carry the full intervention state, the newest version wins, and
 * nothing late, repeated or out of place moves an issue back.
 */
class DispatchEventIntakeTest {

    static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");
    static final UUID A = UUID.fromString("0199f2a0-0000-7000-8000-0000000d0001");
    static final UUID B = UUID.fromString("0199f2a0-0000-7000-8000-0000000d0002");
    static final String CONTRACTOR = "0199f2a0-0000-7000-8000-000000000301";

    /** A running issue and the interventions Issue knows for it, updated as each outcome would be stored. */
    static final class Run {
        Issue issue;
        final List<InterventionProjection> interventions = new ArrayList<>();
        int sequence = 2;

        Run(IssueState state, long version) {
            this.issue = issue(state, version);
        }

        DispatchOutcome apply(DispatchEvent event) {
            DispatchOutcome outcome = DispatchEventIntake.apply(issue, List.copyOf(interventions), event, sequence,
                    "Garage level -1", NOW);
            outcome.issue().ifPresent(next -> issue = next);
            outcome.intervention().ifPresent(next -> {
                interventions.removeIf(p -> p.interventionId().equals(next.interventionId()));
                interventions.add(next);
            });
            if (outcome.history().isPresent()) {
                sequence++;
            }
            return outcome;
        }

        InterventionProjection intervention(UUID id) {
            return interventions.stream().filter(p -> p.interventionId().equals(id)).findFirst().orElseThrow();
        }
    }

    static Issue issue(IssueState state, long version) {
        boolean decided = state != IssueState.RECEIVED && state != IssueState.IN_TRIAGE;
        return Issue.restore(IssueTest.ID, IssueTest.BUILDING, IssueTest.AREA, state, version,
                decided ? IssueCategory.PLUMBING : null, decided ? IssueUrgency.HIGH : null,
                CriticalCheckStatus.COMPLETED, "critical-alerts-v1", null, IssueTest.CREATED, IssueTest.LATER);
    }

    static DispatchEvent event(DispatchEventType type, UUID intervention, long version, InterventionState state) {
        return event(type, intervention, version, state, Optional.empty(), false, Optional.empty());
    }

    static DispatchEvent event(DispatchEventType type, UUID intervention, long version, InterventionState state,
                               Optional<String> code, boolean manual, Optional<String> contractor) {
        Optional<VisitWindow> window = type == DispatchEventType.BOOKING_CONFIRMED
                ? Optional.of(new VisitWindow(Instant.parse("2026-12-02T09:00:00Z"),
                        Instant.parse("2026-12-02T12:00:00Z")))
                : Optional.empty();
        return new DispatchEvent(UUID.randomUUID().toString(), type, intervention, version, IssueTest.BUILDING,
                IssueTest.ID, state, NOW.minusSeconds(60), code, window, manual, contractor);
    }

    static DispatchEvent approved(UUID intervention, long version) {
        return event(DispatchEventType.INTERVENTION_APPROVED, intervention, version, InterventionState.SENT,
                Optional.empty(), false, Optional.of(CONTRACTOR));
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({
        "awaiting_approval,    in_coordination",
        "sent,                 in_coordination",
        "scheduling_pending,   in_coordination",
        "booked,               scheduled",
        "in_progress,          in_progress",
        "completion_reported,  awaiting_validation",
        "declined,             triaged",
        "cancelled,            triaged",
        "cancellation_pending, triaged",
        "closed,               triaged",
    })
    void aTriagedIssueTakesTheStateItsFirstInterventionStandsFor(String interventionState, String issueState) {
        Run run = new Run(IssueState.TRIAGED, 2);
        DispatchOutcome outcome = run.apply(event(DispatchEventType.INTERVENTION_STARTED, A, 3,
                InterventionState.fromValue(interventionState).orElseThrow()));

        assertThat(outcome.record().outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(run.issue.state().value()).isEqualTo(issueState);
        boolean moved = !issueState.equals("triaged");
        assertThat(run.issue.version()).isEqualTo(moved ? 3 : 2);
        assertThat(outcome.events()).hasSize(moved ? 1 : 0);
    }

    @Test
    void theWholeInterventionMovesTheIssueStepByStepAndTellsDispatchOnlyWhenTheStateChanges() {
        Run run = new Run(IssueState.TRIAGED, 2);

        run.apply(approved(A, 2));
        assertThat(run.issue.state()).isEqualTo(IssueState.IN_COORDINATION);
        assertThat(run.issue.version()).isEqualTo(3);

        DispatchOutcome accepted = run.apply(event(DispatchEventType.CONTRACTOR_RESPONDED, A, 3,
                InterventionState.SCHEDULING_PENDING, Optional.of("accept"), false, Optional.empty()));
        // Still in coordination: the projection follows, the issue and its version do not move.
        assertThat(accepted.issue()).isEmpty();
        assertThat(accepted.events()).isEmpty();
        assertThat(run.issue.version()).isEqualTo(3);
        assertThat(run.intervention(A).state()).isEqualTo(InterventionState.SCHEDULING_PENDING);

        run.apply(event(DispatchEventType.BOOKING_CONFIRMED, A, 4, InterventionState.BOOKED));
        assertThat(run.issue.state()).isEqualTo(IssueState.SCHEDULED);
        run.apply(event(DispatchEventType.INTERVENTION_STARTED, A, 5, InterventionState.IN_PROGRESS));
        assertThat(run.issue.state()).isEqualTo(IssueState.IN_PROGRESS);
        DispatchOutcome completed = run.apply(event(DispatchEventType.COMPLETION_REPORTED, A, 6,
                InterventionState.COMPLETION_REPORTED));

        assertThat(run.issue.state()).isEqualTo(IssueState.AWAITING_VALIDATION);
        assertThat(run.issue.version()).isEqualTo(6);
        assertThat(completed.events()).singleElement().isInstanceOfSatisfying(IssueStateChanged.class, changed -> {
            assertThat(changed.state()).isEqualTo(IssueState.AWAITING_VALIDATION);
            assertThat(changed.issueVersion()).isEqualTo(6);
            assertThat(changed.dispatchable()).isFalse();
            assertThat(changed.areaLabel()).isEqualTo("Garage level -1");
        });
        assertThat(completed.history()).hasValueSatisfying(entry -> {
            assertThat(entry.type()).isEqualTo(HistoryEntryType.COMPLETION_REPORTED);
            assertThat(entry.issueVersion()).isEqualTo(6);
            assertThat(entry.occurredAt()).isEqualTo(NOW.minusSeconds(60));
        });
        // The contractor named on approval is kept through every later event.
        assertThat(run.intervention(A).contractorUserId()).contains(CONTRACTOR);
        assertThat(run.intervention(A).lastVersion()).isEqualTo(6);
    }

    @Test
    void aLaterVersionThatArrivesFirstWinsAndTheEarlierOneIsShownInHistoryOnly() {
        Run run = new Run(IssueState.TRIAGED, 2);

        run.apply(event(DispatchEventType.BOOKING_CONFIRMED, A, 4, InterventionState.BOOKED));
        assertThat(run.issue.state()).isEqualTo(IssueState.SCHEDULED);

        DispatchOutcome late = run.apply(approved(A, 2));
        assertThat(late.record().outcome()).isEqualTo(Outcome.HISTORY_ONLY);
        assertThat(late.record().reason()).contains(DispatchEventRecord.STALE_VERSION);
        assertThat(late.issue()).isEmpty();
        assertThat(late.events()).isEmpty();
        assertThat(late.history()).hasValueSatisfying(entry -> {
            assertThat(entry.type()).isEqualTo(HistoryEntryType.INTERVENTION_APPROVED);
            assertThat(entry.issueVersion()).isEqualTo(3);
        });
        assertThat(run.issue.state()).isEqualTo(IssueState.SCHEDULED);
        // The late approval still names the contractor, which the booking did not: only that is taken from it.
        assertThat(run.intervention(A).lastVersion()).isEqualTo(4);
        assertThat(run.intervention(A).state()).isEqualTo(InterventionState.BOOKED);
        assertThat(run.intervention(A).contractorUserId()).contains(CONTRACTOR);
    }

    @Test
    void aLateEventNeverReplacesTheContractorKnown() {
        Run run = new Run(IssueState.TRIAGED, 2);
        run.apply(event(DispatchEventType.INTERVENTION_TERMS_CHANGED, A, 4, InterventionState.AWAITING_APPROVAL,
                Optional.empty(), false, Optional.of("another-contractor")));

        DispatchOutcome late = run.apply(approved(A, 2));

        assertThat(late.intervention()).isEmpty();
        assertThat(run.intervention(A).contractorUserId()).contains("another-contractor");
    }

    @Test
    void anEventWithTheSameVersionAsTheLastOneAppliedChangesNothing() {
        Run run = new Run(IssueState.TRIAGED, 2);
        run.apply(event(DispatchEventType.BOOKING_CONFIRMED, A, 4, InterventionState.BOOKED));

        DispatchOutcome sameVersion = run.apply(event(DispatchEventType.INTERVENTION_STARTED, A, 4,
                InterventionState.IN_PROGRESS));

        assertThat(sameVersion.record().outcome()).isEqualTo(Outcome.HISTORY_ONLY);
        assertThat(sameVersion.record().reason()).contains(DispatchEventRecord.STALE_VERSION);
        assertThat(run.issue.state()).isEqualTo(IssueState.SCHEDULED);
        assertThat(run.issue.version()).isEqualTo(3);
    }

    @Test
    void aBookingStateIsShownInHistoryOnlyAndNeverMovesTheIntervention() {
        Run run = new Run(IssueState.TRIAGED, 2);
        run.apply(approved(A, 2));
        run.apply(event(DispatchEventType.CONTRACTOR_RESPONDED, A, 3, InterventionState.SCHEDULING_PENDING,
                Optional.of("accept"), false, Optional.empty()));

        // Another booking request moves the intervention's version, not its state (BookingService.requestAgain).
        DispatchOutcome requested = run.apply(event(DispatchEventType.BOOKING_STATE_CHANGED, A, 4,
                InterventionState.SCHEDULING_PENDING, Optional.of("requested"), false, Optional.empty()));

        assertThat(requested.record().outcome()).isEqualTo(Outcome.HISTORY_ONLY);
        assertThat(requested.record().reason()).contains(DispatchEventRecord.BOOKING_ONLY);
        assertThat(requested.intervention()).isEmpty();
        assertThat(requested.issue()).isEmpty();
        assertThat(requested.history()).hasValueSatisfying(entry ->
                assertThat(entry.type()).isEqualTo(HistoryEntryType.BOOKING_STATE_CHANGED));
        assertThat(run.intervention(A).lastVersion()).isEqualTo(3);

        run.apply(event(DispatchEventType.BOOKING_CONFIRMED, A, 5, InterventionState.BOOKED));
        assertThat(run.issue.state()).isEqualTo(IssueState.SCHEDULED);
    }

    @Test
    void aBookingStateOfAnInterventionIssueDoesNotKnowYetDoesNotMakeItActive() {
        Run run = new Run(IssueState.TRIAGED, 2);

        DispatchOutcome failed = run.apply(event(DispatchEventType.BOOKING_STATE_CHANGED, A, 2,
                InterventionState.SCHEDULING_PENDING, Optional.of("failed"), false, Optional.empty()));

        assertThat(failed.record().reason()).contains(DispatchEventRecord.BOOKING_ONLY);
        assertThat(run.interventions).isEmpty();
        assertThat(run.issue.state()).isEqualTo(IssueState.TRIAGED);
    }

    /**
     * Approving a counterproposal goes straight to {@code scheduling_pending}; when the booking request fails at
     * once, Dispatch writes {@code InterventionApproved} and {@code BookingStateChanged(failed)} with one version.
     */
    @ParameterizedTest(name = "[{index}] booking state first: {0}")
    @CsvSource({"false", "true"})
    void anApprovalAndAFailedBookingWithOneVersionApplyTheApprovalInEitherOrder(boolean bookingFirst) {
        Run run = new Run(IssueState.TRIAGED, 2);
        DispatchEvent approval = event(DispatchEventType.INTERVENTION_APPROVED, A, 3,
                InterventionState.SCHEDULING_PENDING, Optional.empty(), false, Optional.of(CONTRACTOR));
        DispatchEvent booking = event(DispatchEventType.BOOKING_STATE_CHANGED, A, 3,
                InterventionState.SCHEDULING_PENDING, Optional.of("failed"), false, Optional.empty());

        DispatchOutcome approved;
        if (bookingFirst) {
            run.apply(booking);
            approved = run.apply(approval);
        } else {
            approved = run.apply(approval);
            run.apply(booking);
        }

        assertThat(approved.record().outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(run.issue.state()).isEqualTo(IssueState.IN_COORDINATION);
        assertThat(run.intervention(A).lastVersion()).isEqualTo(3);
        assertThat(run.intervention(A).contractorUserId()).contains(CONTRACTOR);
    }

    @Test
    void aDeclineEndsTheInterventionSoTheIssueNeedsAContractorAndTheNextOneBecomesActive() {
        Run run = new Run(IssueState.TRIAGED, 2);
        run.apply(approved(A, 2));

        DispatchOutcome declined = run.apply(event(DispatchEventType.INTERVENTION_DECLINED, A, 3,
                InterventionState.DECLINED, Optional.of("unavailable"), false, Optional.empty()));
        assertThat(run.issue.state()).isEqualTo(IssueState.TRIAGED);
        assertThat(run.issue.version()).isEqualTo(4);
        assertThat(declined.events()).singleElement().isInstanceOfSatisfying(IssueStateChanged.class,
                changed -> assertThat(changed.dispatchable()).isTrue());

        DispatchOutcome next = run.apply(approved(B, 2));
        assertThat(next.record().outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(run.issue.state()).isEqualTo(IssueState.IN_COORDINATION);

        // The declined one never comes back, however new its version.
        DispatchOutcome ended = run.apply(event(DispatchEventType.INTERVENTION_CANCELLED, A, 9,
                InterventionState.CANCELLED, Optional.of("other"), false, Optional.empty()));
        assertThat(ended.record().outcome()).isEqualTo(Outcome.HISTORY_ONLY);
        assertThat(ended.record().reason()).contains(DispatchEventRecord.ENDED_INTERVENTION);
        assertThat(run.issue.state()).isEqualTo(IssueState.IN_COORDINATION);
        assertThat(run.intervention(A).state()).isEqualTo(InterventionState.DECLINED);
    }

    /**
     * Dispatch keeps one active intervention per issue, so a second one means the event that ended the first is
     * still on its way: A approved, B approved, then A declined.
     */
    @Test
    void aSecondActiveInterventionWritesNothingAndIsAppliedOnceTheFirstHasEnded() {
        Run run = new Run(IssueState.TRIAGED, 2);
        run.apply(approved(A, 2));

        DispatchOutcome second = run.apply(approved(B, 2));

        assertThat(second.retryLaterNeeded()).isTrue();
        assertThat(second.history()).isEmpty();
        assertThat(second.issue()).isEmpty();
        assertThat(second.intervention()).isEmpty();
        assertThat(second.events()).isEmpty();
        assertThatThrownBy(second::record).isInstanceOf(IllegalStateException.class);
        assertThat(run.interventions).extracting(InterventionProjection::interventionId).containsExactly(A);

        run.apply(event(DispatchEventType.INTERVENTION_DECLINED, A, 3, InterventionState.DECLINED,
                Optional.of("unavailable"), false, Optional.empty()));
        DispatchOutcome again = run.apply(approved(B, 2));

        assertThat(again.record().outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(run.issue.state()).isEqualTo(IssueState.IN_COORDINATION);
        assertThat(run.intervention(B).contractorUserId()).contains(CONTRACTOR);
    }

    @Test
    void anOutcomeDeliveredAgainLaterCarriesNothingToWrite() {
        assertThatThrownBy(() -> new DispatchOutcome(Optional.empty(), Optional.empty(),
                Optional.of(IssueHistoryEntry.dispatch(issue(IssueState.TRIAGED, 2), 2,
                        HistoryEntryType.INTERVENTION_APPROVED, HistoryVisibility.PUBLIC, "administrator", NOW)),
                Optional.empty(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"received", "in_triage"})
    void anInterventionForAnIssueNotYetTriagedFails(String state) {
        Run run = new Run(IssueState.fromValue(state), 1);

        DispatchOutcome outcome = run.apply(approved(A, 2));

        assertThat(outcome.record().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(outcome.record().reason()).contains(DispatchEventRecord.NOT_DISPATCHABLE);
        assertThat(run.issue.state().value()).isEqualTo(state);
        assertThat(run.interventions).isEmpty();
    }

    @Test
    void anEventFromAnotherBuildingFails() {
        Run run = new Run(IssueState.TRIAGED, 2);
        DispatchEvent elsewhere = new DispatchEvent("e-1", DispatchEventType.INTERVENTION_APPROVED, A, 2,
                new BuildingId(UUID.randomUUID()), IssueTest.ID, InterventionState.SENT, NOW, Optional.empty(),
                Optional.empty(), false, Optional.of(CONTRACTOR));

        DispatchOutcome outcome = run.apply(elsewhere);

        assertThat(outcome.record().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(outcome.record().reason()).contains(DispatchEventRecord.BUILDING_MISMATCH);
        assertThat(run.issue.state()).isEqualTo(IssueState.TRIAGED);
    }

    @ParameterizedTest
    @CsvSource({"resolved", "cancelled"})
    void aClosedIssueNeverMovesButItsInterventionStillEnds(String state) {
        Run run = new Run(IssueState.TRIAGED, 2);
        run.apply(approved(A, 2));
        run.apply(event(DispatchEventType.BOOKING_CONFIRMED, A, 4, InterventionState.BOOKED));
        run.issue = issue(IssueState.fromValue(state), 5);

        // Dispatch cancels the intervention because the issue was closed (IssueResolved, IssueCancelled).
        DispatchOutcome cancelled = run.apply(event(DispatchEventType.INTERVENTION_CANCELLED, A, 5,
                InterventionState.CANCELLED, Optional.of("issue_" + state), false, Optional.empty()));
        assertThat(cancelled.record().outcome()).isEqualTo(Outcome.HISTORY_ONLY);
        assertThat(cancelled.record().reason()).contains(DispatchEventRecord.ISSUE_CLOSED);
        assertThat(cancelled.issue()).isEmpty();
        assertThat(cancelled.history()).hasValueSatisfying(entry ->
                assertThat(entry.actorRole()).isEqualTo("system"));
        assertThat(run.intervention(A).state()).isEqualTo(InterventionState.CANCELLED);

        // A late event of that intervention, or of one Issue never saw, does not reopen anything.
        DispatchOutcome late = run.apply(event(DispatchEventType.INTERVENTION_STARTED, A, 6,
                InterventionState.IN_PROGRESS));
        DispatchOutcome unknown = run.apply(approved(B, 2));
        DispatchOutcome booking = run.apply(event(DispatchEventType.BOOKING_STATE_CHANGED, A, 7,
                InterventionState.CANCELLED, Optional.of("cancelled"), false, Optional.empty()));
        assertThat(booking.intervention()).isEmpty();
        assertThat(late.record().outcome()).isEqualTo(Outcome.HISTORY_ONLY);
        assertThat(unknown.record().outcome()).isEqualTo(Outcome.HISTORY_ONLY);
        assertThat(unknown.intervention()).isEmpty();
        assertThat(run.issue.state().value()).isEqualTo(state);
        assertThat(run.issue.version()).isEqualTo(5);
        assertThat(run.intervention(A).state()).isEqualTo(InterventionState.CANCELLED);
    }

    @Test
    void aLateApprovalOfAClosedIssueStillGivesTheContractor() {
        Run run = new Run(IssueState.TRIAGED, 2);
        run.apply(event(DispatchEventType.BOOKING_CONFIRMED, A, 4, InterventionState.BOOKED));
        run.issue = issue(IssueState.RESOLVED, 4);

        DispatchOutcome late = run.apply(approved(A, 2));

        assertThat(late.record().reason()).contains(DispatchEventRecord.ISSUE_CLOSED);
        assertThat(run.intervention(A).contractorUserId()).contains(CONTRACTOR);
        assertThat(run.intervention(A).lastVersion()).isEqualTo(4);
    }

    @Test
    void reworkTakesTheIssueBackToInProgress() {
        Run run = new Run(IssueState.TRIAGED, 2);
        run.apply(event(DispatchEventType.COMPLETION_REPORTED, A, 6, InterventionState.COMPLETION_REPORTED));
        assertThat(run.issue.state()).isEqualTo(IssueState.AWAITING_VALIDATION);

        run.apply(event(DispatchEventType.REWORK_REQUESTED, A, 7, InterventionState.IN_PROGRESS));

        assertThat(run.issue.state()).isEqualTo(IssueState.IN_PROGRESS);
    }

    @Test
    void aPendingCancellationKeepsTheIssueStateUntilTheCancellationEnds() {
        Run run = new Run(IssueState.TRIAGED, 2);
        run.apply(event(DispatchEventType.BOOKING_CONFIRMED, A, 4, InterventionState.BOOKED));

        DispatchOutcome pending = run.apply(event(DispatchEventType.INTERVENTION_CANCELLATION_PENDING, A, 5,
                InterventionState.CANCELLATION_PENDING, Optional.of("rescheduling"), false, Optional.empty()));
        assertThat(pending.issue()).isEmpty();
        assertThat(run.issue.state()).isEqualTo(IssueState.SCHEDULED);
        assertThat(run.intervention(A).state()).isEqualTo(InterventionState.CANCELLATION_PENDING);

        run.apply(event(DispatchEventType.INTERVENTION_CANCELLED, A, 6, InterventionState.CANCELLED,
                Optional.of("rescheduling"), false, Optional.empty()));
        assertThat(run.issue.state()).isEqualTo(IssueState.TRIAGED);
    }

    @ParameterizedTest(name = "[{index}] {0} {1} -> {2}, {3}, {4}")
    @CsvSource({
        "InterventionApproved,            ,                intervention_approved,             public,   administrator",
        "ContractorResponded,             accept,          contractor_accepted,               public,   contractor",
        "ContractorResponded,             counterpropose,  contractor_counterproposed,        internal, contractor",
        "InterventionDeclined,            unavailable,     contractor_declined,               public,   contractor",
        "InterventionTermsChanged,        ,                intervention_terms_changed,        internal, administrator",
        "BookingConfirmed,                calendar,        booking_confirmed,                 public,   system",
        "BookingConfirmed,                manual,          booking_confirmed_manually,        public,   administrator",
        "BookingStateChanged,             failed,          booking_state_changed,             internal, system",
        "InterventionStarted,             ,                intervention_started,              public,   contractor",
        "CompletionReported,              ,                completion_reported,               public,   contractor",
        "ReworkRequested,                 ,                rework_requested,                  public,   administrator",
        "InterventionCancellationPending, rescheduling,    intervention_cancellation_pending, public,   administrator",
        "InterventionCancelled,           no_longer_needed, intervention_cancelled,           public,   administrator",
        "InterventionCancelled,           issue_resolved,  intervention_cancelled,            public,   system",
    })
    void eachEventIsShownAsTheEntryTheClientWords(
            String type, String detail, String entryType, String visibility, String actor) {
        boolean booking = type.equals("BookingConfirmed");
        DispatchEvent event = event(DispatchEventType.fromValue(type).orElseThrow(), A, 3, InterventionState.SENT,
                booking ? Optional.empty() : Optional.ofNullable(detail), booking && detail.equals("manual"),
                Optional.empty());

        DispatchOutcome outcome = new Run(IssueState.TRIAGED, 2).apply(event);

        assertThat(outcome.history()).hasValueSatisfying(entry -> {
            assertThat(entry.type().value()).isEqualTo(entryType);
            assertThat(entry.visibility()).isEqualTo(
                    visibility.equals("public") ? HistoryVisibility.PUBLIC : HistoryVisibility.INTERNAL);
            assertThat(entry.actorRole()).isEqualTo(actor);
        });
    }

    @Test
    void termsChangedNamingTheContractorReplacesTheOneKnownAndOneWithoutKeepsIt() {
        Run run = new Run(IssueState.TRIAGED, 2);
        run.apply(approved(A, 2));
        run.apply(event(DispatchEventType.INTERVENTION_TERMS_CHANGED, A, 3, InterventionState.AWAITING_APPROVAL));
        assertThat(run.intervention(A).contractorUserId()).contains(CONTRACTOR);

        run.apply(event(DispatchEventType.INTERVENTION_TERMS_CHANGED, A, 4, InterventionState.AWAITING_APPROVAL,
                Optional.empty(), false, Optional.of("another-contractor")));
        assertThat(run.intervention(A).contractorUserId()).contains("another-contractor");
    }

    @Test
    void anEventForAnotherIssueIsAProgrammingError() {
        DispatchEvent other = new DispatchEvent("e-1", DispatchEventType.INTERVENTION_APPROVED, A, 2,
                IssueTest.BUILDING, new pt.isep.bmc.issue.domain.issue.IssueId(UUID.randomUUID()),
                InterventionState.SENT, NOW, Optional.empty(), Optional.empty(), false, Optional.empty());

        assertThatThrownBy(() -> DispatchEventIntake.apply(issue(IssueState.TRIAGED, 2), List.of(), other, 2,
                "Garage", NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aResolvedIssueIsNeverMovedByFollowIntervention() {
        assertThatThrownBy(() -> issue(IssueState.RESOLVED, 5).followIntervention(IssueState.IN_PROGRESS, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(issue(IssueState.SCHEDULED, 4).followIntervention(IssueState.SCHEDULED, NOW)).isEmpty();
    }
}
