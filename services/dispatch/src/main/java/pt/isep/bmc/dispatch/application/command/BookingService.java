package pt.isep.bmc.dispatch.application.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import pt.isep.bmc.dispatch.application.idempotency.IdempotentExecutor;
import pt.isep.bmc.dispatch.application.port.in.BookingUseCases;
import pt.isep.bmc.dispatch.application.port.out.BookingRepository;
import pt.isep.bmc.dispatch.application.port.out.ContractorCatalogue;
import pt.isep.bmc.dispatch.application.port.out.EventOutbox;
import pt.isep.bmc.dispatch.application.port.out.IdGenerator;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyRecord;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyScope;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyStore;
import pt.isep.bmc.dispatch.application.port.out.InterventionRepository;
import pt.isep.bmc.dispatch.application.port.out.IssueProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.MembershipProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.OutboxEvent;
import pt.isep.bmc.dispatch.application.port.out.TransactionRunner;
import pt.isep.bmc.dispatch.domain.booking.Booking;
import pt.isep.bmc.dispatch.domain.booking.BookingCommands;
import pt.isep.bmc.dispatch.domain.booking.BookingCommands.CancellationRequest;
import pt.isep.bmc.dispatch.domain.booking.BookingCommands.ManualRequest;
import pt.isep.bmc.dispatch.domain.booking.BookingCommands.Outcome;
import pt.isep.bmc.dispatch.domain.booking.BookingId;
import pt.isep.bmc.dispatch.domain.booking.BookingSource;
import pt.isep.bmc.dispatch.domain.event.BookingConfirmed;
import pt.isep.bmc.dispatch.domain.event.BookingStateChanged;
import pt.isep.bmc.dispatch.domain.event.DomainEvent;
import pt.isep.bmc.dispatch.domain.event.InterventionCancellationPending;
import pt.isep.bmc.dispatch.domain.event.InterventionCancelled;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.projection.AdministratorAccess;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * The administrator's booking commands (US-08) and the cancellation of an
 * intervention (technical design 3.2), with {@code If-Match} and an
 * idempotency key, except the reconciliation request, which only asks the
 * worker for a check.
 *
 * <p>Each command locks the issue projection row, then the intervention, then
 * its latest booking: the order of every other command and of the booking
 * worker (which needs no issue row), so none of them deadlock.
 */
public final class BookingService implements BookingUseCases {

    public static final String MANUAL_OPERATION = "POST /interventions/{id}/manual-confirmation";
    public static final String REQUEST_OPERATION = "POST /interventions/{id}/booking";
    public static final String CANCEL_OPERATION = "POST /interventions/{id}/cancellation";
    static final Duration KEY_RETENTION = Duration.ofHours(24);
    private static final int OK = 200;

    private final TransactionRunner transactions;
    private final IssueProjectionRepository issues;
    private final MembershipProjectionRepository memberships;
    private final ContractorCatalogue catalogue;
    private final InterventionRepository interventions;
    private final BookingRepository bookings;
    private final EventOutbox outbox;
    private final IdempotencyStore idempotency;
    private final IdempotentExecutor idempotent;
    private final IdGenerator ids;
    private final Clock clock;

    public BookingService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            MembershipProjectionRepository memberships,
            ContractorCatalogue catalogue,
            InterventionRepository interventions,
            BookingRepository bookings,
            EventOutbox outbox,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        this.transactions = transactions;
        this.issues = issues;
        this.memberships = memberships;
        this.catalogue = catalogue;
        this.interventions = interventions;
        this.bookings = bookings;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.idempotent = new IdempotentExecutor(transactions, idempotency);
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public BookingCommandResult confirmManually(ManualConfirmationCommand command) {
        IdempotencyScope scope = new IdempotencyScope(command.actor(), MANUAL_OPERATION, command.idempotencyKey());
        String hash = IdempotentExecutor.fingerprint(command.interventionId(), command.expectedVersion(),
                command.windowStart(), command.windowEnd(), command.source(), command.reason());
        Instant now = now();
        return idempotent.execute(scope, hash, now, () -> {
            idempotency.record(record(scope, hash, command.interventionId().value(), command.expectedVersion(), now));
            Locked locked = lock(command.actor(), command.interventionId());
            ManualRequest request = BookingCommands.validateManual(command.windowStart(), command.windowEnd(),
                    command.source(), command.reason(), now);
            Booking latest = locked.latest().orElseThrow(() ->
                    new InvalidTransitionException(locked.intervention().state().value()));
            Outcome outcome = BookingCommands.confirmManually(locked.access(), locked.intervention(), latest, request,
                    command.expectedVersion(), now);
            Intervention booked = save(outcome);
            Booking confirmed = outcome.booking().orElseThrow();
            append(new BookingConfirmed(booked.id().value(), booked.version(), booked.buildingId(), now,
                    booked.issueId(), confirmed.bookedWindow(), BookingSource.MANUAL, booked.state()),
                    command.correlationId());
            return result(booked, Optional.of(confirmed), false);
        }, record -> replay(command.actor(), command.interventionId(), record));
    }

    @Override
    public BookingCommandResult requestAgain(RequestBookingCommand command) {
        IdempotencyScope scope = new IdempotencyScope(command.actor(), REQUEST_OPERATION, command.idempotencyKey());
        String hash = IdempotentExecutor.fingerprint(command.interventionId(), command.expectedVersion());
        Instant now = now();
        return idempotent.execute(scope, hash, now, () -> {
            idempotency.record(record(scope, hash, command.interventionId().value(), command.expectedVersion(), now));
            Locked locked = lock(command.actor(), command.interventionId());
            Booking latest = locked.latest().orElseThrow(() ->
                    new InvalidTransitionException(locked.intervention().state().value()));
            Outcome outcome = BookingCommands.requestAgain(locked.access(), locked.intervention(), latest,
                    new BookingId(ids.newId()), catalogue.findCalendarRef(locked.intervention().contractorId()),
                    command.expectedVersion(), now);
            Intervention touched = save(outcome);
            Booking requested = outcome.booking().orElseThrow();
            append(new BookingStateChanged(touched.id().value(), touched.version(), touched.buildingId(), now,
                    touched.issueId(), requested.state(), touched.state()), command.correlationId());
            return result(touched, Optional.of(requested), false);
        }, record -> replay(command.actor(), command.interventionId(), record));
    }

    @Override
    public BookingCommandResult reconcile(UserId actor, InterventionId interventionId) {
        Instant now = now();
        return transactions.inTransaction(() -> {
            Locked locked = lock(actor, interventionId);
            Booking latest = locked.latest().orElseThrow(() ->
                    new InvalidTransitionException(locked.intervention().state().value()));
            Booking asked = BookingCommands.reconcile(locked.access(), locked.intervention(), latest, now);
            bookings.update(asked);
            return result(locked.intervention(), Optional.of(asked), false);
        });
    }

    @Override
    public BookingCommandResult cancel(CancelInterventionCommand command) {
        IdempotencyScope scope = new IdempotencyScope(command.actor(), CANCEL_OPERATION, command.idempotencyKey());
        String hash = IdempotentExecutor.fingerprint(command.interventionId(), command.expectedVersion(),
                command.reasonCode(), command.note());
        Instant now = now();
        return idempotent.execute(scope, hash, now, () -> {
            idempotency.record(record(scope, hash, command.interventionId().value(), command.expectedVersion(), now));
            Locked locked = lock(command.actor(), command.interventionId());
            CancellationRequest request = BookingCommands.validateCancellation(command.reasonCode(), command.note());
            Outcome outcome = BookingCommands.cancel(locked.access(), locked.intervention(), locked.latest(), request,
                    command.expectedVersion(), now);
            Intervention next = save(outcome);
            DomainEvent event = next.state() == InterventionState.CANCELLATION_PENDING
                    ? new InterventionCancellationPending(next.id().value(), next.version(), next.buildingId(), now,
                            next.issueId(), request.reason(), next.state())
                    : new InterventionCancelled(next.id().value(), next.version(), next.buildingId(), now,
                            next.issueId(), request.reason(), next.state());
            append(event, command.correlationId());
            return result(next, outcome.booking().or(locked::latest), false);
        }, record -> replay(command.actor(), command.interventionId(), record));
    }

    /** The intervention and its latest booking, locked in the documented order, for an administrator. */
    private record Locked(AdministratorAccess access, Intervention intervention, Optional<Booking> latest) {
    }

    private Locked lock(UserId actor, InterventionId id) {
        Intervention unlocked = interventions.find(id).orElseThrow(AccessDeniedException::new);
        AdministratorAccess access = AdministratorAccess.of(actor, memberships.findByUser(actor));
        access.requireAdministers(unlocked.buildingId());
        issues.findForUpdate(unlocked.issueId())
                .orElseThrow(() -> new IllegalStateException("intervention points at an issue missing from the projection"));
        Intervention current = interventions.findForUpdate(id).orElseThrow(AccessDeniedException::new);
        return new Locked(access, current, bookings.findLatestForUpdate(id));
    }

    private Intervention save(Outcome outcome) {
        Intervention next = outcome.intervention();
        interventions.update(next);
        outcome.booking().ifPresent(booking -> {
            if (booking.version() == 1) {
                bookings.add(booking);
            } else {
                bookings.update(booking);
            }
        });
        return next;
    }

    /** The original result, for a caller who still administers the building, with the state as it is now. */
    private BookingCommandResult replay(UserId actor, InterventionId id, IdempotencyRecord record) {
        Intervention intervention = interventions.find(id)
                .orElseThrow(() -> new IllegalStateException("idempotency record points at a missing intervention"));
        AdministratorAccess.of(actor, memberships.findByUser(actor)).requireAdministers(intervention.buildingId());
        return new BookingCommandResult(intervention.id(), intervention.state(), record.resourceVersion(),
                Optional.empty(), true);
    }

    private static BookingCommandResult result(Intervention intervention, Optional<Booking> booking, boolean replayed) {
        return new BookingCommandResult(intervention.id(), intervention.state(), intervention.version(),
                booking.map(Booking::state), replayed);
    }

    private static IdempotencyRecord record(
            IdempotencyScope scope, String hash, UUID resource, long expectedVersion, Instant now) {
        return new IdempotencyRecord(scope, hash, OK, resource, expectedVersion + 1, now, now.plus(KEY_RETENTION));
    }

    private void append(DomainEvent event, String correlationId) {
        outbox.append(new OutboxEvent(ids.newId(), event, correlationId));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
