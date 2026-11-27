package pt.isep.bmc.dispatch.application.command;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import pt.isep.bmc.dispatch.application.port.in.ApplyIssueClosureUseCase;
import pt.isep.bmc.dispatch.application.port.out.BookingRepository;
import pt.isep.bmc.dispatch.application.port.out.EventOutbox;
import pt.isep.bmc.dispatch.application.port.out.IdGenerator;
import pt.isep.bmc.dispatch.application.port.out.InterventionRepository;
import pt.isep.bmc.dispatch.application.port.out.IssueProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.OutboxEvent;
import pt.isep.bmc.dispatch.application.port.out.TransactionRunner;
import pt.isep.bmc.dispatch.domain.booking.Booking;
import pt.isep.bmc.dispatch.domain.event.InterventionCancellationPending;
import pt.isep.bmc.dispatch.domain.event.InterventionCancelled;
import pt.isep.bmc.dispatch.domain.intervention.CancelReason;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;

/**
 * Ends the issue's intervention when Issue resolves or cancels the issue
 * (US-09, technical design 3.2 and 4.3 step 8). In one transaction holding
 * the issue projection row's lock, as intervention creation does, so either
 * creation sees the issue closed or this sees the new intervention:
 * <ul>
 *   <li>{@code completion_reported} becomes {@code closed}, with no event:
 *       Issue made the closure and already knows;</li>
 *   <li>an earlier state is cancelled ({@code issue_resolved} or {@code
 *       issue_cancelled}), through {@code cancellation_pending} while a
 *       booking that exists or may exist is cancelled at the calendar by the
 *       booking worker;</li>
 *   <li>a terminal or already pending intervention does not change, so a
 *       repeated or late event has no effect.</li>
 * </ul>
 * Deduplication by event ID is the messaging starter's (EN-02-02); this is
 * idempotent by state before it is wired.
 */
public final class IssueClosureService implements ApplyIssueClosureUseCase {

    private final TransactionRunner transactions;
    private final IssueProjectionRepository issues;
    private final InterventionRepository interventions;
    private final BookingRepository bookings;
    private final EventOutbox outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public IssueClosureService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            InterventionRepository interventions,
            BookingRepository bookings,
            EventOutbox outbox,
            IdGenerator ids,
            Clock clock) {
        this.transactions = transactions;
        this.issues = issues;
        this.interventions = interventions;
        this.bookings = bookings;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public int apply(IssueClosure closure) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        CancelReason reason = closure.kind() == IssueClosure.Kind.RESOLVED
                ? CancelReason.ISSUE_RESOLVED
                : CancelReason.ISSUE_CANCELLED;
        return transactions.inTransaction(() -> {
            issues.findForUpdate(closure.issueId());
            // The unique key on the active intervention leaves at most one non-terminal intervention per issue.
            Optional<InterventionId> active = interventions.findActiveForIssue(closure.issueId());
            if (active.isEmpty()) {
                return 0;
            }
            Intervention current = interventions.findForUpdate(active.get()).orElseThrow();
            Optional<Booking> latest = bookings.findLatestForUpdate(current.id()).filter(Booking::isActive);
            Optional<Booking.CancellationStart> start = current.state() == InterventionState.COMPLETION_REPORTED
                    ? Optional.empty()
                    : latest.map(b -> b.startCancellation(now));
            boolean needsCalendar = start.map(Booking.CancellationStart::needsCalendar).orElse(false);
            Optional<Intervention> ended = current.endedByIssue(reason, needsCalendar, now);
            if (ended.isEmpty()) {
                return 0;
            }
            Intervention next = ended.get();
            interventions.update(next);
            start.map(Booking.CancellationStart::booking).ifPresent(bookings::update);
            switch (next.state()) {
                case CANCELLATION_PENDING -> append(new InterventionCancellationPending(next.id().value(),
                        next.version(), next.buildingId(), now, next.issueId(), reason, next.state()), closure);
                case CANCELLED -> append(new InterventionCancelled(next.id().value(), next.version(),
                        next.buildingId(), now, next.issueId(), reason, next.state()), closure);
                default -> {
                    // closed: no event.
                }
            }
            return 1;
        });
    }

    private void append(pt.isep.bmc.dispatch.domain.event.DomainEvent event, IssueClosure closure) {
        outbox.append(new OutboxEvent(ids.newId(), event, closure.correlationId()));
    }
}
