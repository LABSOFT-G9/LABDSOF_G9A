package pt.isep.bmc.dispatch.domain.booking;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import pt.isep.bmc.dispatch.domain.intervention.CancelReason;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.intervention.ProposalFields;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;
import pt.isep.bmc.dispatch.domain.projection.AdministratorAccess;
import pt.isep.bmc.dispatch.domain.shared.FieldViolation;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;

/**
 * The administrator's booking commands (US-08) and the cancellation of an
 * intervention (technical design 3.2). Each runs its checks in the order of
 * the other commands: who ({@code 404}), the fields ({@code 400}), the
 * intervention's state and version ({@code 409}), then the booking's state
 * ({@code 409 INVALID_TRANSITION} with the booking's state).
 */
public final class BookingCommands {

    public static final int REASON_MAX = 500;

    private BookingCommands() {
    }

    // Fields

    /** A manual confirmation: the agreed window, how it was agreed and why (all required). */
    public static ManualRequest validateManual(
            String windowStart, String windowEnd, String source, String reason, Instant now) {
        List<FieldViolation> violations = new ArrayList<>();
        Optional<TimeWindow> window = ProposalFields.window(windowStart, windowEnd, now, violations);
        Optional<ManualSource> parsed = Optional.empty();
        if (source == null || source.isBlank()) {
            violations.add(new FieldViolation("source", "required"));
        } else {
            try {
                parsed = Optional.of(ManualSource.fromValue(source));
            } catch (IllegalArgumentException e) {
                violations.add(new FieldViolation("source", "invalid"));
            }
        }
        Optional<String> text = ProposalFields.text(reason, "reason", 1, REASON_MAX, violations);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        return new ManualRequest(window.orElseThrow(), parsed.orElseThrow(), text.orElseThrow());
    }

    /** A cancellation: a reason code and an optional note (1–500 characters). */
    public static CancellationRequest validateCancellation(String reasonCode, String note) {
        List<FieldViolation> violations = new ArrayList<>();
        Optional<CancelReason> reason = Optional.empty();
        if (reasonCode == null || reasonCode.isBlank()) {
            violations.add(new FieldViolation("reasonCode", "required"));
        } else {
            reason = CancelReason.fromValue(reasonCode);
            if (reason.isEmpty()) {
                violations.add(new FieldViolation("reasonCode", "invalid"));
            }
        }
        Optional<String> text = note == null
                ? Optional.empty()
                : ProposalFields.text(note, "note", 1, REASON_MAX, violations);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        return new CancellationRequest(reason.orElseThrow(), text);
    }

    // Commands

    /**
     * Confirms the booking by hand: the intervention is {@code booked} and the
     * booking {@code confirmed} with source {@code manual}.
     */
    public static Outcome confirmManually(
            AdministratorAccess access, Intervention intervention, Booking booking, ManualRequest request,
            long expectedVersion, Instant now) {
        access.requireAdministers(intervention.buildingId());
        Intervention booked = intervention.bookManually(expectedVersion, now);
        Booking confirmed = booking.confirmManually(
                new ManualConfirmation(request.window(), request.source(), request.reason(), access.userId(), now), now);
        return new Outcome(booked, Optional.of(confirmed), false);
    }

    /**
     * Another booking request for the same agreed window, after the last one
     * was {@code rejected} or {@code failed}. The old booking stays in history.
     */
    public static Outcome requestAgain(
            AdministratorAccess access, Intervention intervention, Booking latest, BookingId newId,
            Optional<String> contractorRef, long expectedVersion, Instant now) {
        access.requireAdministers(intervention.buildingId());
        Intervention touched = intervention.touchWhileScheduling(expectedVersion, now);
        if (latest.state() != BookingState.REJECTED && latest.state() != BookingState.FAILED) {
            throw new InvalidTransitionException(latest.state().value());
        }
        return new Outcome(touched, Optional.of(Booking.request(newId, intervention.id(), contractorRef,
                latest.buildingCode(), latest.summary(), latest.window(), now)), false);
    }

    /** Asks the worker to check a calendar-confirmed booking now (on demand). Changes no version. */
    public static Booking reconcile(AdministratorAccess access, Intervention intervention, Booking booking, Instant now) {
        access.requireAdministers(intervention.buildingId());
        intervention.requireState(Set.of(InterventionState.BOOKED, InterventionState.IN_PROGRESS));
        return booking.requestVerification(now);
    }

    /**
     * Cancels the intervention (technical design 3.2). A booking that exists
     * or may exist at the calendar is cancelled there first, and the
     * intervention waits in {@code cancellation_pending}.
     *
     * @param booking the intervention's latest booking, if it has one
     */
    public static Outcome cancel(
            AdministratorAccess access, Intervention intervention, Optional<Booking> booking,
            CancellationRequest request, long expectedVersion, Instant now) {
        access.requireAdministers(intervention.buildingId());
        Optional<Booking.CancellationStart> start = booking.filter(Booking::isActive).map(b -> b.startCancellation(now));
        boolean needsCalendar = start.map(Booking.CancellationStart::needsCalendar).orElse(false);
        Intervention next = intervention.cancel(expectedVersion, request.reason(), request.note(), needsCalendar, now);
        return new Outcome(next, start.map(Booking.CancellationStart::booking), needsCalendar);
    }

    public record ManualRequest(TimeWindow window, ManualSource source, String reason) {

        public ManualRequest {
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String toString() {
            return "ManualRequest[source=" + source + "]";
        }
    }

    public record CancellationRequest(CancelReason reason, Optional<String> note) {

        @Override
        public String toString() {
            return "CancellationRequest[reason=" + reason + "]";
        }
    }

    /**
     * @param booking         the booking to save (new or changed), if any
     * @param pendingCalendar for a cancellation: whether the calendar must be called first
     */
    public record Outcome(Intervention intervention, Optional<Booking> booking, boolean pendingCalendar) {
    }
}
