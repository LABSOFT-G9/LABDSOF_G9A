package pt.isep.bmc.issue.domain.dispatch;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.issue.domain.event.DomainEvent;
import pt.isep.bmc.issue.domain.event.IssueStateChanged;
import pt.isep.bmc.issue.domain.issue.HistoryEntryType;
import pt.isep.bmc.issue.domain.issue.HistoryVisibility;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;
import pt.isep.bmc.issue.domain.issue.IssueState;

/**
 * What a Dispatch event does to an issue (US-07-03; technical design 3.3,
 * "Applying Dispatch events"). The broker does not keep order across retries,
 * so Issue never needs every event in sequence: each one carries the full new
 * intervention state, and the newest version wins.
 *
 * <ol>
 *   <li>The event's building must be the issue's; otherwise it {@code failed}.</li>
 *   <li>A resolved or cancelled issue never moves: the event is shown in
 *       history only, and Dispatch is ending the intervention anyway. Its
 *       projection still follows, so a reopened issue has no active one left.</li>
 *   <li>An event of an intervention Issue knows is applied when it is newer
 *       than the last version applied for it, and while the intervention has
 *       not ended. Otherwise it is shown in history only.</li>
 *   <li>An intervention Issue does not know becomes the active one when the
 *       issue is {@code triaged} and has none. A second active intervention, or
 *       one for an issue that is not {@code triaged}, {@code failed}.</li>
 *   <li>Applied, the issue takes the state the intervention state stands for
 *       ({@link InterventionState#issueState()}), on its next version, and
 *       {@code IssueStateChanged} tells Dispatch. A declined or cancelled
 *       intervention ends, so the issue is {@code triaged} with none, and the
 *       next intervention can become active.</li>
 * </ol>
 *
 * <p>For example, {@code BookingConfirmed} (version 4) arriving before
 * {@code InterventionApproved} (version 2) moves a {@code triaged} issue to
 * {@code scheduled}, and the late version 2 is shown in history only.
 */
public final class DispatchEventIntake {

    private DispatchEventIntake() {
    }

    /**
     * @param interventions every intervention Issue knows for this issue
     * @param sequence      the sequence of the history entry, if the event gets one
     * @param areaLabel     the issue's area, for {@code IssueStateChanged}
     */
    public static DispatchOutcome apply(
            Issue issue, List<InterventionProjection> interventions, DispatchEvent event, int sequence,
            String areaLabel, Instant now) {
        Objects.requireNonNull(issue, "issue");
        Objects.requireNonNull(event, "event");
        if (!issue.id().equals(event.issueId())) {
            throw new IllegalArgumentException("the event names another issue");
        }
        if (!issue.buildingId().equals(event.buildingId())) {
            return failed(event, DispatchEventRecord.BUILDING_MISMATCH, now);
        }
        Optional<InterventionProjection> known = interventions.stream()
                .filter(p -> p.interventionId().equals(event.interventionId()))
                .findFirst();

        if (!issue.state().isOpen()) {
            Optional<InterventionProjection> followed = known
                    .filter(p -> !p.state().isTerminal() && event.aggregateVersion() > p.lastVersion())
                    .map(p -> p.next(event, now));
            return historyOnly(issue, followed, event, DispatchEventRecord.ISSUE_CLOSED, sequence, now);
        }
        if (known.isPresent()) {
            InterventionProjection projection = known.get();
            if (event.aggregateVersion() <= projection.lastVersion()) {
                return historyOnly(issue, Optional.empty(), event, DispatchEventRecord.STALE_VERSION, sequence, now);
            }
            if (projection.state().isTerminal()) {
                return historyOnly(issue, Optional.empty(), event, DispatchEventRecord.ENDED_INTERVENTION, sequence,
                        now);
            }
            return applied(issue, projection.next(event, now), event, sequence, areaLabel, now);
        }
        if (interventions.stream().anyMatch(p -> !p.state().isTerminal())) {
            return failed(event, DispatchEventRecord.SECOND_INTERVENTION, now);
        }
        if (issue.state() != IssueState.TRIAGED) {
            return failed(event, DispatchEventRecord.NOT_DISPATCHABLE, now);
        }
        return applied(issue, InterventionProjection.first(event, now), event, sequence, areaLabel, now);
    }

    private static DispatchOutcome applied(
            Issue issue, InterventionProjection projection, DispatchEvent event, int sequence, String areaLabel,
            Instant now) {
        Optional<Issue> moved = event.interventionState().issueState()
                .flatMap(target -> issue.followIntervention(target, now));
        Issue after = moved.orElse(issue);
        List<DomainEvent> events = moved.<List<DomainEvent>>map(m -> List.of(IssueStateChanged.of(m, areaLabel)))
                .orElse(List.of());
        return new DispatchOutcome(moved, Optional.of(projection), Optional.of(entry(after, event, sequence)),
                new DispatchEventRecord(event, DispatchEventRecord.Outcome.APPLIED, Optional.empty(),
                        Optional.of(sequence), now),
                events);
    }

    private static DispatchOutcome historyOnly(
            Issue issue, Optional<InterventionProjection> projection, DispatchEvent event, String reason, int sequence,
            Instant now) {
        return new DispatchOutcome(Optional.empty(), projection, Optional.of(entry(issue, event, sequence)),
                new DispatchEventRecord(event, DispatchEventRecord.Outcome.HISTORY_ONLY, Optional.of(reason),
                        Optional.of(sequence), now),
                List.of());
    }

    private static DispatchOutcome failed(DispatchEvent event, String reason, Instant now) {
        return new DispatchOutcome(Optional.empty(), Optional.empty(), Optional.empty(),
                new DispatchEventRecord(event, DispatchEventRecord.Outcome.FAILED, Optional.of(reason),
                        Optional.empty(), now),
                List.of());
    }

    private static IssueHistoryEntry entry(Issue issue, DispatchEvent event, int sequence) {
        HistoryEntryType type = historyType(event);
        return IssueHistoryEntry.dispatch(issue, sequence, type, visibility(type), actorRole(event),
                event.occurredAt());
    }

    /** The entry type the client words; the names follow the ones it already shows (US-02-02). */
    static HistoryEntryType historyType(DispatchEvent event) {
        return switch (event.type()) {
            case INTERVENTION_APPROVED -> HistoryEntryType.INTERVENTION_APPROVED;
            case CONTRACTOR_RESPONDED -> event.code().filter("counterpropose"::equals).isPresent()
                    ? HistoryEntryType.CONTRACTOR_COUNTERPROPOSED
                    : HistoryEntryType.CONTRACTOR_ACCEPTED;
            case INTERVENTION_DECLINED -> HistoryEntryType.CONTRACTOR_DECLINED;
            case INTERVENTION_TERMS_CHANGED -> HistoryEntryType.INTERVENTION_TERMS_CHANGED;
            case BOOKING_CONFIRMED -> event.manual()
                    ? HistoryEntryType.BOOKING_CONFIRMED_MANUALLY
                    : HistoryEntryType.BOOKING_CONFIRMED;
            case BOOKING_STATE_CHANGED -> HistoryEntryType.BOOKING_STATE_CHANGED;
            case INTERVENTION_STARTED -> HistoryEntryType.INTERVENTION_STARTED;
            case COMPLETION_REPORTED -> HistoryEntryType.COMPLETION_REPORTED;
            case REWORK_REQUESTED -> HistoryEntryType.REWORK_REQUESTED;
            case INTERVENTION_CANCELLATION_PENDING -> HistoryEntryType.INTERVENTION_CANCELLATION_PENDING;
            case INTERVENTION_CANCELLED -> HistoryEntryType.INTERVENTION_CANCELLED;
        };
    }

    /**
     * Residents see how the work goes: approved, accepted or declined, booked, started, completed, cancelled. The
     * negotiation (counterproposals, changed terms) and the booking's calendar troubles are for administrators.
     */
    static HistoryVisibility visibility(HistoryEntryType type) {
        return switch (type) {
            case CONTRACTOR_COUNTERPROPOSED, INTERVENTION_TERMS_CHANGED, BOOKING_STATE_CHANGED ->
                    HistoryVisibility.INTERNAL;
            default -> HistoryVisibility.PUBLIC;
        };
    }

    /** Who acted. A cancellation that follows the issue's own closure is the system's, not a person's. */
    static String actorRole(DispatchEvent event) {
        return switch (event.type()) {
            case CONTRACTOR_RESPONDED, INTERVENTION_DECLINED, INTERVENTION_STARTED, COMPLETION_REPORTED ->
                    IssueHistoryEntry.ROLE_CONTRACTOR;
            case BOOKING_CONFIRMED -> event.manual() ? IssueHistoryEntry.ROLE_ADMINISTRATOR
                    : IssueHistoryEntry.ROLE_SYSTEM;
            case BOOKING_STATE_CHANGED -> IssueHistoryEntry.ROLE_SYSTEM;
            case INTERVENTION_CANCELLATION_PENDING, INTERVENTION_CANCELLED ->
                    event.code().filter(c -> c.equals("issue_resolved") || c.equals("issue_cancelled")).isPresent()
                            ? IssueHistoryEntry.ROLE_SYSTEM
                            : IssueHistoryEntry.ROLE_ADMINISTRATOR;
            case INTERVENTION_APPROVED, INTERVENTION_TERMS_CHANGED, REWORK_REQUESTED ->
                    IssueHistoryEntry.ROLE_ADMINISTRATOR;
        };
    }
}
