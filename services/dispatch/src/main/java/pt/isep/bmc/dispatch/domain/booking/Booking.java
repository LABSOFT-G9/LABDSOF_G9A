package pt.isep.bmc.dispatch.domain.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;

/**
 * A booking of an intervention's agreed window (technical design 2.2 and
 * 4.5). A separate aggregate, so calendar retries and reconciliation never
 * lock the intervention. Immutable: every change returns the next version.
 *
 * <p>Nothing is ever shown as confirmed without evidence: a valid calendar
 * answer, or an administrator's manual confirmation, which is labelled as
 * such. A booking that may exist at the calendar is reconciled before any
 * re-send, and a confirmed booking that disappears is a {@code discrepancy},
 * never silently recreated.
 *
 * @param contractorRef the contractor's reference in the calendar
 * @param buildingCode  what the calendar is told about the place: the building ID, never an address
 * @param summary       a sanitised summary such as "Plumbing intervention", never report text
 * @param window        the window requested from the calendar: the agreed proposal's
 * @param externalRef   the calendar's reference, once known
 * @param reasonCode    why the booking is {@code rejected}, {@code failed} or a {@code discrepancy}
 * @param action        the worker's next call, if any, due at {@code nextAttemptAt}
 * @param attempts      calls made for the current action
 */
public record Booking(
        BookingId id,
        InterventionId interventionId,
        String contractorRef,
        String buildingCode,
        String summary,
        TimeWindow window,
        BookingState state,
        Optional<BookingSource> source,
        Optional<String> externalRef,
        Optional<String> reasonCode,
        Optional<BookingAction> action,
        int attempts,
        Optional<Instant> nextAttemptAt,
        Optional<Instant> lastReconciledAt,
        Optional<ManualConfirmation> manual,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Technical design 4.6: five attempts, waiting 5, 10, 20 and 40 seconds;
     * then {@code failed}, but only for a booking known not to be at the
     * calendar. A booking that may exist there ({@code uncertain}) and the
     * check after a manual confirmation keep looking, at most 2 minutes
     * apart, until the calendar answers.
     */
    public static final int MAX_ATTEMPTS = 5;
    static final Duration FIRST_WAIT = Duration.ofSeconds(5);
    static final Duration MAX_WAIT = Duration.ofMinutes(2);
    /** A confirmed calendar booking is checked again once its window starts within this time. */
    public static final Duration VERIFY_BEFORE_WINDOW = Duration.ofHours(24);

    public static final String SLOT_CONFLICT = "slot_conflict";
    public static final String CONTRACT_DEFECT = "contract_defect";
    public static final String RETRIES_EXHAUSTED = "retries_exhausted";
    public static final String WINDOW_PASSED = "window_passed";
    public static final String NO_CALENDAR = "no_calendar_reference";
    public static final String CANCELLED_AT_PROVIDER = "cancelled_at_provider";
    public static final String MISSING_AT_PROVIDER = "missing_at_provider";
    public static final String FOUND_AFTER_MANUAL = "found_after_manual";
    public static final String WINDOW_MISMATCH = "window_mismatch";
    public static final String CANCEL_REFUSED = "cancel_refused";

    private static final Set<BookingState> ENDED =
            EnumSet.of(BookingState.REJECTED, BookingState.FAILED, BookingState.CANCELLED);

    public Booking {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(interventionId, "interventionId");
        Objects.requireNonNull(buildingCode, "buildingCode");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(lastReconciledAt, "lastReconciledAt");
        Objects.requireNonNull(manual, "manual");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (action.isPresent() != nextAttemptAt.isPresent()) {
            throw new IllegalArgumentException("an action has a due time, and only an action");
        }
    }

    /**
     * A new booking request for the agreed window, saved before any call
     * (US-08): {@code requested}, to be sent at once. Without a calendar
     * reference, or with a window that has started, it fails straight away,
     * so the administrator confirms by hand or cancels.
     */
    public static Booking request(
            BookingId id, InterventionId interventionId, Optional<String> contractorRef, String buildingCode,
            String summary, TimeWindow window, Instant now) {
        String ref = contractorRef.orElse("");
        Optional<String> failure = ref.isBlank()
                ? Optional.of(NO_CALENDAR)
                : window.start().isAfter(now) ? Optional.empty() : Optional.of(WINDOW_PASSED);
        if (failure.isPresent()) {
            return new Booking(id, interventionId, ref, buildingCode, summary, window, BookingState.FAILED, Optional.empty(), Optional.empty(),
                    failure, Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), 1, now, now);
        }
        return new Booking(id, interventionId, ref, buildingCode, summary, window, BookingState.REQUESTED, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(BookingAction.CREATE), 0, Optional.of(now),
                Optional.empty(), Optional.empty(), 1, now, now);
    }

    public boolean isActive() {
        return !ENDED.contains(state);
    }

    /**
     * The worker takes the booking for its next call: one more attempt, and
     * not due again until {@code lease} has passed, so another worker does
     * not call at the same time. The result is applied only to this version.
     */
    public Booking claim(Instant now, Duration lease) {
        if (action.isEmpty()) {
            throw new IllegalStateException("nothing to do for this booking");
        }
        return next(state, source, externalRef, reasonCode, action, attempts + 1, Optional.of(now.plus(lease)),
                lastReconciledAt, manual, now);
    }

    /**
     * Applies a calendar reply to the action just claimed (technical design
     * 4.5, outcome table).
     */
    public BookingStep afterReply(BookingAction called, CalendarReply reply, Instant now) {
        return switch (called) {
            case CREATE, RECONCILE -> afterCreateOrLookup(called, reply, now);
            case VERIFY -> afterVerify(reply, now);
            case ORPHAN_CHECK -> afterOrphanCheck(reply, now);
            case CANCEL -> afterCancel(reply, now);
        };
    }

    private BookingStep afterCreateOrLookup(BookingAction called, CalendarReply reply, Instant now) {
        return switch (reply) {
            case CalendarReply.Booked booked -> new BookingStep(confirmedByCalendar(booked.externalRef(), now),
                    BookingStep.Effect.CONFIRMED);
            case CalendarReply.Mismatched m ->
                    changed(discrepancy(WINDOW_MISMATCH, Optional.of(m.externalRef()), now));
            case CalendarReply.Conflict c -> changed(end(BookingState.REJECTED, SLOT_CONFLICT, now));
            case CalendarReply.Defect d -> changed(end(BookingState.FAILED, CONTRACT_DEFECT, now));
            case CalendarReply.Cancelled c -> changed(end(BookingState.FAILED, CANCELLED_AT_PROVIDER, now));
            case CalendarReply.Unavailable u -> retry(called, state, now);
            case CalendarReply.Unknown u -> retry(BookingAction.RECONCILE, BookingState.UNCERTAIN, now);
            case CalendarReply.Absent a ->
                    throw new IllegalArgumentException("an absent booking is re-sent, not applied");
            case CalendarReply.UnknownReference r -> throw new IllegalArgumentException("not an answer to a create");
        };
    }

    private BookingStep afterVerify(CalendarReply reply, Instant now) {
        return switch (reply) {
            case CalendarReply.Booked booked -> unchanged(next(state, source, externalRef, reasonCode,
                    Optional.empty(), 0, Optional.empty(), Optional.of(now), manual, now));
            case CalendarReply.Mismatched m -> changed(discrepancy(WINDOW_MISMATCH, externalRef, now));
            case CalendarReply.Absent a -> changed(discrepancy(MISSING_AT_PROVIDER, externalRef, now));
            case CalendarReply.Cancelled c -> changed(discrepancy(MISSING_AT_PROVIDER, externalRef, now));
            default -> giveUpAfterMax(BookingAction.VERIFY, now);
        };
    }

    private BookingStep afterOrphanCheck(CalendarReply reply, Instant now) {
        return switch (reply) {
            case CalendarReply.Booked booked ->
                    changed(discrepancy(FOUND_AFTER_MANUAL, Optional.of(booked.externalRef()), now));
            case CalendarReply.Mismatched m ->
                    changed(discrepancy(FOUND_AFTER_MANUAL, Optional.of(m.externalRef()), now));
            case CalendarReply.Absent a -> unchanged(reconciled(now));
            case CalendarReply.Cancelled c -> unchanged(reconciled(now));
            // The earlier request may have been booked: never stop looking without an answer.
            default -> unchanged(next(state, source, externalRef, reasonCode, Optional.of(BookingAction.ORPHAN_CHECK),
                    attempts, Optional.of(now.plus(wait(attempts))), lastReconciledAt, manual, now));
        };
    }

    private BookingStep afterCancel(CalendarReply reply, Instant now) {
        return switch (reply) {
            case CalendarReply.Booked booked -> unchanged(next(state, source, Optional.of(booked.externalRef()),
                    reasonCode, Optional.of(BookingAction.CANCEL), 0, Optional.of(now), lastReconciledAt, manual, now));
            case CalendarReply.Cancelled c -> cancelled(now);
            case CalendarReply.UnknownReference r -> cancelled(now);
            case CalendarReply.Absent a -> cancelled(now);
            // 422: the calendar will never accept it. The intervention is cancelled; the booking is left
            // as a discrepancy, still holding its reference, for someone to cancel at the calendar by hand.
            case CalendarReply.Defect d -> new BookingStep(discrepancy(CANCEL_REFUSED, externalRef, now),
                    BookingStep.Effect.CANCELLED);
            // Cancellation stays pending until the outcome is checked: no attempt limit.
            case CalendarReply.Unavailable u -> retryCancel(now);
            case CalendarReply.Unknown u -> retryCancel(now);
            case CalendarReply.Mismatched m -> retryCancel(now);
            case CalendarReply.Conflict c -> retryCancel(now);
        };
    }

    private BookingStep retryCancel(Instant now) {
        return unchanged(next(state, source, externalRef, reasonCode, Optional.of(BookingAction.CANCEL), attempts,
                Optional.of(now.plus(wait(attempts))), lastReconciledAt, manual, now));
    }

    /**
     * The worker found the window started before the request reached the
     * calendar ({@code CREATE} due, or a lookup found nothing): the booking is
     * never sent late. It fails, so the administrator confirms by hand or
     * cancels.
     */
    public BookingStep windowPassed(Instant now) {
        if (state != BookingState.REQUESTED && state != BookingState.UNCERTAIN) {
            throw new IllegalStateException("only a booking not yet at the calendar can miss its window");
        }
        return changed(end(BookingState.FAILED, WINDOW_PASSED, now));
    }

    /** Whether the window has started, so nothing more may be sent to the calendar for it. */
    public boolean windowStarted(Instant now) {
        return !window.start().isAfter(now);
    }

    /**
     * An administrator confirms the booking by hand (US-08): {@code confirmed},
     * source {@code manual}, no more automatic creation. A request that may
     * still have reached the calendar is looked up once more, and a booking
     * found there becomes a {@code discrepancy}, never a second confirmation.
     */
    public Booking confirmManually(ManualConfirmation confirmation, Instant now) {
        if (!EnumSet.of(BookingState.REQUESTED, BookingState.UNCERTAIN, BookingState.REJECTED, BookingState.FAILED)
                .contains(state)) {
            throw new InvalidTransitionException(state.value());
        }
        boolean outstanding = state == BookingState.REQUESTED || state == BookingState.UNCERTAIN;
        Optional<BookingAction> check = outstanding ? Optional.of(BookingAction.ORPHAN_CHECK) : Optional.empty();
        return next(BookingState.CONFIRMED, Optional.of(BookingSource.MANUAL), Optional.empty(), Optional.empty(),
                check, 0, check.map(a -> afterAnyCallInFlight(now)), lastReconciledAt, Optional.of(confirmation), now);
    }

    /** The window that is booked: the manual one if confirmed by hand, otherwise the requested one. */
    public TimeWindow bookedWindow() {
        return manual.map(ManualConfirmation::window).orElse(window);
    }

    /** The administrator asks for a check now (on demand): only a booking the calendar confirmed. */
    public Booking requestVerification(Instant now) {
        if (state != BookingState.CONFIRMED || source.orElse(null) != BookingSource.CALENDAR) {
            throw new InvalidTransitionException(state.value());
        }
        return next(state, source, externalRef, reasonCode, Optional.of(BookingAction.VERIFY), 0, Optional.of(now),
                lastReconciledAt, manual, now);
    }

    /**
     * The intervention is being cancelled. A booking that exists or may exist
     * at the calendar is cancelled there first ({@code true}: the
     * intervention waits in {@code cancellation_pending}); any other booking
     * simply ends.
     */
    public CancellationStart startCancellation(Instant now) {
        boolean mayExist = switch (state) {
            case REQUESTED, UNCERTAIN -> true;
            case CONFIRMED -> source.orElse(null) == BookingSource.CALENDAR
                    || action.orElse(null) == BookingAction.ORPHAN_CHECK;
            case DISCREPANCY -> Set.of(FOUND_AFTER_MANUAL, WINDOW_MISMATCH).contains(reasonCode.orElse(""));
            default -> false;
        };
        if (!mayExist) {
            Booking ended = isActive() ? end(BookingState.CANCELLED, null, now) : this;
            return new CancellationStart(ended, false);
        }
        return new CancellationStart(next(state, source, externalRef, reasonCode, Optional.of(BookingAction.CANCEL),
                0, Optional.of(afterAnyCallInFlight(now)), lastReconciledAt, manual, now), true);
    }

    /**
     * When a new lookup may run: not before a create or reconciliation that a
     * worker may still be making has ended (its lease), or the lookup could
     * miss a booking the calendar is about to make. The lease is the claimed
     * booking's due time; a {@code CREATE} or {@code RECONCILE} waiting to be
     * retried is also kept, at most 2 minutes.
     */
    private Instant afterAnyCallInFlight(Instant now) {
        boolean mayBeCreating = action.filter(a -> a == BookingAction.CREATE || a == BookingAction.RECONCILE).isPresent();
        return mayBeCreating ? nextAttemptAt.filter(due -> due.isAfter(now)).orElse(now) : now;
    }

    /** @param needsCalendar whether the calendar must be called before the intervention is cancelled */
    public record CancellationStart(Booking booking, boolean needsCalendar) {
    }

    private Booking confirmedByCalendar(String ref, Instant now) {
        Instant verifyAt = window.start().minus(VERIFY_BEFORE_WINDOW);
        boolean verifyLater = verifyAt.isAfter(now);
        return next(BookingState.CONFIRMED, Optional.of(BookingSource.CALENDAR), Optional.of(ref), Optional.empty(),
                verifyLater ? Optional.of(BookingAction.VERIFY) : Optional.empty(), 0,
                verifyLater ? Optional.of(verifyAt) : Optional.empty(), Optional.of(now), manual, now);
    }

    private BookingStep retry(BookingAction nextAction, BookingState nextState, Instant now) {
        // Only a booking known not to be at the calendar may fail: an uncertain one is looked up until answered.
        if (attempts >= MAX_ATTEMPTS && nextState != BookingState.UNCERTAIN) {
            return changed(end(BookingState.FAILED, RETRIES_EXHAUSTED, now));
        }
        Booking waiting = next(nextState, source, externalRef, reasonCode, Optional.of(nextAction), attempts,
                Optional.of(now.plus(wait(attempts))), lastReconciledAt, manual, now);
        return nextState == state ? unchanged(waiting) : changed(waiting);
    }

    private BookingStep giveUpAfterMax(BookingAction current, Instant now) {
        if (attempts >= MAX_ATTEMPTS) {
            return unchanged(next(state, source, externalRef, reasonCode, Optional.empty(), 0, Optional.empty(),
                    lastReconciledAt, manual, now));
        }
        return unchanged(next(state, source, externalRef, reasonCode, Optional.of(current), attempts,
                Optional.of(now.plus(wait(attempts))), lastReconciledAt, manual, now));
    }

    private BookingStep cancelled(Instant now) {
        return new BookingStep(end(BookingState.CANCELLED, null, now), BookingStep.Effect.CANCELLED);
    }

    private Booking reconciled(Instant now) {
        return next(state, source, externalRef, reasonCode, Optional.empty(), 0, Optional.empty(), Optional.of(now),
                manual, now);
    }

    private Booking discrepancy(String reason, Optional<String> ref, Instant now) {
        return next(BookingState.DISCREPANCY, source, ref, Optional.of(reason), Optional.empty(), 0,
                Optional.empty(), Optional.of(now), manual, now);
    }

    private Booking end(BookingState ended, String reason, Instant now) {
        return next(ended, source, externalRef, Optional.ofNullable(reason), Optional.empty(), 0, Optional.empty(),
                lastReconciledAt, manual, now);
    }

    /** After attempt {@code n} (1-based) failed: 5 s, doubling, at most 2 minutes. */
    static Duration wait(int attempt) {
        Duration wait = FIRST_WAIT.multipliedBy(1L << Math.min(Math.max(attempt - 1, 0), 10));
        return wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
    }

    private static BookingStep changed(Booking booking) {
        return new BookingStep(booking, BookingStep.Effect.STATE_CHANGED);
    }

    private static BookingStep unchanged(Booking booking) {
        return new BookingStep(booking, BookingStep.Effect.NONE);
    }

    private Booking next(
            BookingState nextState, Optional<BookingSource> nextSource, Optional<String> nextRef,
            Optional<String> nextReason, Optional<BookingAction> nextAction, int nextAttempts, Optional<Instant> due,
            Optional<Instant> reconciledAt, Optional<ManualConfirmation> nextManual, Instant now) {
        return new Booking(id, interventionId, contractorRef, buildingCode, summary, window, nextState, nextSource,
                nextRef, nextReason, nextAction, nextAttempts, due, reconciledAt, nextManual, version + 1, createdAt, now);
    }
}
