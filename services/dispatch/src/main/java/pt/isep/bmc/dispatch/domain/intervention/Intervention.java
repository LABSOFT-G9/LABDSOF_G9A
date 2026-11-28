package pt.isep.bmc.dispatch.domain.intervention;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.BusinessRuleException;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;
import pt.isep.bmc.dispatch.domain.shared.VersionConflictException;

/**
 * An intervention for an issue (technical design 2.2 and 3.2). Immutable:
 * every change returns the next version.
 *
 * <p>Every command checks the state first and the version second, as in
 * technical design 3.3: a command that is no longer allowed answers {@code
 * INVALID_TRANSITION} whatever version it was based on.
 *
 * @param approvedProposalVersion the version sent to the contractor and not superseded since; empty while a
 *                                draft, after changed terms or after a counterproposal
 * @param agreedProposalVersion   the version both parties agreed, from acceptance or from approving a
 *                                contractor's counterproposal; what US-08 books
 * @param contractorContacted     whether a request was ever sent to the contractor; from then on the contractor
 *                                sees the intervention and cannot be replaced
 * @param cancelReason            why the intervention was cancelled, once a cancellation started
 * @param cancelNote              the administrator's optional text with it; never in an event
 * @param buildingId              snapshot of the issue's building, taken from the issue projection at creation
 *                                (technical design 4.3 step 4)
 * @param projectedIssueVersion   the issue version of the projection row used for the check at creation
 * @param projectionAppliedAt     when that projection row was written
 */
public record Intervention(
        InterventionId id,
        IssueId issueId,
        BuildingId buildingId,
        ContractorId contractorId,
        InterventionState state,
        long version,
        int currentProposalVersion,
        Optional<Integer> approvedProposalVersion,
        Optional<Integer> agreedProposalVersion,
        boolean contractorContacted,
        Optional<CancelReason> cancelReason,
        Optional<String> cancelNote,
        long projectedIssueVersion,
        Instant projectionAppliedAt,
        UserId createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static final long INITIAL_VERSION = 1;

    public Intervention {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(contractorId, "contractorId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(approvedProposalVersion, "approvedProposalVersion");
        Objects.requireNonNull(agreedProposalVersion, "agreedProposalVersion");
        Objects.requireNonNull(cancelReason, "cancelReason");
        Objects.requireNonNull(cancelNote, "cancelNote");
        Objects.requireNonNull(projectionAppliedAt, "projectionAppliedAt");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < INITIAL_VERSION) {
            throw new IllegalArgumentException("intervention version must be at least 1");
        }
    }

    /** A new draft (US-06): {@code awaiting_approval}, version 1, proposal version 1, nothing sent. */
    public static Intervention draft(
            InterventionId id, IssueId issueId, BuildingId buildingId, ContractorId contractorId,
            long projectedIssueVersion, Instant projectionAppliedAt, UserId createdBy, Instant now) {
        return new Intervention(id, issueId, buildingId, contractorId, InterventionState.AWAITING_APPROVAL,
                INITIAL_VERSION, 1, Optional.empty(), Optional.empty(), false, Optional.empty(), Optional.empty(),
                projectedIssueVersion,
                projectionAppliedAt, createdBy, now, now);
    }

    /**
     * The administrator selects another contractor for the draft (US-06).
     * Allowed only in {@code awaiting_approval} and before anything was sent:
     * once a contractor has received the request, another contractor needs a
     * new intervention (US-07).
     *
     * @param expectedVersion the version the administrator was looking at ({@code If-Match})
     * @throws InvalidTransitionException if the intervention is no longer a draft
     * @throws VersionConflictException   if it changed since the administrator read it
     * @throws BusinessRuleException      {@code CONTRACTOR_ALREADY_CONTACTED} once a request was sent
     */
    public Intervention replaceContractor(long expectedVersion, ContractorId contractor, Instant now) {
        Objects.requireNonNull(contractor, "contractor");
        requireCurrent(expectedVersion, Set.of(InterventionState.AWAITING_APPROVAL));
        if (contractorContacted) {
            throw BusinessRuleException.contractorAlreadyContacted();
        }
        return next(contractor, state, currentProposalVersion, approvedProposalVersion, agreedProposalVersion,
                contractorContacted, now);
    }

    /**
     * The administrator approves the current proposal version (US-07). An
     * administrator's version is sent: {@code sent}, waiting for the
     * contractor. A contractor's counterproposal is the contractor's own
     * acceptance, so approving it agrees the terms: {@code scheduling_pending}.
     *
     * @param proposal the current proposal version
     * @throws ValidationException {@code proposalVersion} {@code not_current} if the request names another version
     */
    public Intervention approve(long expectedVersion, int proposalVersion, ProposalVersion proposal, Instant now) {
        requireCurrent(expectedVersion, Set.of(InterventionState.AWAITING_APPROVAL));
        requireNamed(proposalVersion, currentProposalVersion);
        if (proposal.number() != currentProposalVersion) {
            throw new IllegalArgumentException("the proposal is not the current version");
        }
        Optional<Integer> approved = Optional.of(proposalVersion);
        return proposal.byContractor()
                ? next(contractorId, InterventionState.SCHEDULING_PENDING, currentProposalVersion, approved, approved,
                        true, now)
                : next(contractorId, InterventionState.SENT, currentProposalVersion, approved, Optional.empty(),
                        true, now);
    }

    /** The contractor accepts the approved version (US-07): {@code scheduling_pending}, agreed. */
    public Intervention accept(long expectedVersion, int proposalVersion, Instant now) {
        int approved = requireAnswerable(expectedVersion, proposalVersion);
        return next(contractorId, InterventionState.SCHEDULING_PENDING, currentProposalVersion,
                approvedProposalVersion, Optional.of(approved), contractorContacted, now);
    }

    /** The contractor declines (US-07): {@code declined}, terminal. The issue may get another intervention. */
    public Intervention decline(long expectedVersion, int proposalVersion, Instant now) {
        requireAnswerable(expectedVersion, proposalVersion);
        return next(contractorId, InterventionState.DECLINED, currentProposalVersion, approvedProposalVersion,
                Optional.empty(), contractorContacted, now);
    }

    /**
     * The contractor answers with other terms (US-07): a new proposal version
     * that needs the administrator's approval, so back to {@code
     * awaiting_approval} with no approved version.
     */
    public Intervention counterpropose(long expectedVersion, int proposalVersion, Instant now) {
        requireAnswerable(expectedVersion, proposalVersion);
        return next(contractorId, InterventionState.AWAITING_APPROVAL, currentProposalVersion + 1, Optional.empty(),
                Optional.empty(), contractorContacted, now);
    }

    /**
     * The administrator changes the terms before the contractor's answer
     * (US-07): a new version, and any earlier approval no longer counts, so
     * the contractor can no longer answer it.
     */
    public Intervention changeTerms(long expectedVersion, Instant now) {
        requireCurrent(expectedVersion, Set.of(InterventionState.AWAITING_APPROVAL, InterventionState.SENT));
        return next(contractorId, InterventionState.AWAITING_APPROVAL, currentProposalVersion + 1, Optional.empty(),
                Optional.empty(), contractorContacted, now);
    }

    /**
     * The agreed window is booked (US-08), by the calendar or by hand:
     * {@code booked}. A late confirmation for an intervention that is no
     * longer {@code scheduling_pending}, for example one being cancelled,
     * changes nothing.
     */
    public Optional<Intervention> booked(Instant now) {
        if (state != InterventionState.SCHEDULING_PENDING) {
            return Optional.empty();
        }
        return Optional.of(next(contractorId, InterventionState.BOOKED, currentProposalVersion,
                approvedProposalVersion, agreedProposalVersion, contractorContacted, now));
    }

    /** An administrator confirmed the booking by hand (US-08): from {@code scheduling_pending} to {@code booked}. */
    public Intervention bookManually(long expectedVersion, Instant now) {
        requireCurrent(expectedVersion, Set.of(InterventionState.SCHEDULING_PENDING));
        return next(contractorId, InterventionState.BOOKED, currentProposalVersion, approvedProposalVersion,
                agreedProposalVersion, contractorContacted, now);
    }

    /**
     * An administrator command on the booking (US-08: another booking request): allowed while {@code scheduling_pending}, from the
     * version the administrator saw. The version moves.
     */
    public Intervention touchWhileScheduling(long expectedVersion, Instant now) {
        requireCurrent(expectedVersion, Set.of(InterventionState.SCHEDULING_PENDING));
        return next(contractorId, state, currentProposalVersion, approvedProposalVersion, agreedProposalVersion,
                contractorContacted, now);
    }

    /** The state and version check of a command that changes nothing else, such as a reconciliation request. */
    public void requireState(Set<InterventionState> allowed) {
        if (!allowed.contains(state)) {
            throw new InvalidTransitionException(state.value());
        }
    }

    /**
     * The administrator cancels (technical design 3.2). Before anything was
     * agreed it is {@code cancelled} at once. From {@code scheduling_pending}
     * on, a booking that exists or may exist is cancelled at the calendar
     * first: {@code cancellation_pending} until that is checked.
     *
     * @param bookingNeedsCalendar whether the active booking must be cancelled at the calendar
     */
    public Intervention cancel(
            long expectedVersion, CancelReason reason, Optional<String> note, boolean bookingNeedsCalendar,
            Instant now) {
        requireCurrent(expectedVersion, Set.of(InterventionState.AWAITING_APPROVAL, InterventionState.SENT,
                InterventionState.SCHEDULING_PENDING, InterventionState.BOOKED, InterventionState.IN_PROGRESS));
        boolean early = state == InterventionState.AWAITING_APPROVAL || state == InterventionState.SENT;
        InterventionState target = !early && bookingNeedsCalendar
                ? InterventionState.CANCELLATION_PENDING
                : InterventionState.CANCELLED;
        return new Intervention(id, issueId, buildingId, contractorId, target, version + 1, currentProposalVersion,
                approvedProposalVersion, agreedProposalVersion, contractorContacted, Optional.of(reason), note,
                projectedIssueVersion, projectionAppliedAt, createdBy, createdAt, now);
    }

    /** The calendar confirmed the booking's cancellation: {@code cancellation_pending} becomes {@code cancelled}. */
    public Optional<Intervention> cancellationCompleted(Instant now) {
        if (state != InterventionState.CANCELLATION_PENDING) {
            return Optional.empty();
        }
        return Optional.of(next(contractorId, InterventionState.CANCELLED, currentProposalVersion,
                approvedProposalVersion, agreedProposalVersion, contractorContacted, now));
    }

    /** The contractor starts the work (technical design 3.2): from {@code booked} to {@code in_progress}. Optional. */
    public Intervention start(long expectedVersion, Instant now) {
        requireCurrent(expectedVersion, Set.of(InterventionState.BOOKED));
        return next(contractorId, InterventionState.IN_PROGRESS, currentProposalVersion, approvedProposalVersion,
                agreedProposalVersion, contractorContacted, now);
    }

    /**
     * The work is reported complete (US-09): {@code completion_reported}.
     * Not a closure: only Issue closes the issue.
     */
    public Intervention reportCompletion(long expectedVersion, Instant now) {
        requireCurrent(expectedVersion, Set.of(InterventionState.BOOKED, InterventionState.IN_PROGRESS));
        return next(contractorId, InterventionState.COMPLETION_REPORTED, currentProposalVersion,
                approvedProposalVersion, agreedProposalVersion, contractorContacted, now);
    }

    /** The administrator sends a reported completion back (US-09): {@code in_progress} again. */
    public Intervention requestRework(long expectedVersion, Instant now) {
        requireCurrent(expectedVersion, Set.of(InterventionState.COMPLETION_REPORTED));
        return next(contractorId, InterventionState.IN_PROGRESS, currentProposalVersion, approvedProposalVersion,
                agreedProposalVersion, contractorContacted, now);
    }

    /**
     * Issue resolved or cancelled the issue (technical design 3.2 and 4.3
     * step 8). A reported completion is {@code closed}. Anything earlier is
     * cancelled, through {@code cancellation_pending} while a booking that
     * exists or may exist is cancelled at the calendar. A terminal or pending
     * intervention does not change, so a repeated event has no effect.
     *
     * @param bookingNeedsCalendar whether the active booking must be cancelled at the calendar
     */
    public Optional<Intervention> endedByIssue(CancelReason reason, boolean bookingNeedsCalendar, Instant now) {
        InterventionState target = switch (state) {
            case COMPLETION_REPORTED -> InterventionState.CLOSED;
            case AWAITING_APPROVAL, SENT -> InterventionState.CANCELLED;
            case SCHEDULING_PENDING, BOOKED, IN_PROGRESS -> bookingNeedsCalendar
                    ? InterventionState.CANCELLATION_PENDING
                    : InterventionState.CANCELLED;
            case CANCELLATION_PENDING, DECLINED, CANCELLED, CLOSED -> null;
        };
        if (target == null) {
            return Optional.empty();
        }
        Optional<CancelReason> recorded = target == InterventionState.CLOSED ? cancelReason : Optional.of(reason);
        return Optional.of(new Intervention(id, issueId, buildingId, contractorId, target, version + 1,
                currentProposalVersion, approvedProposalVersion, agreedProposalVersion, contractorContacted, recorded,
                target == InterventionState.CLOSED ? cancelNote : Optional.empty(), projectedIssueVersion,
                projectionAppliedAt, createdBy, createdAt, now));
    }

    private void requireCurrent(long expectedVersion, Set<InterventionState> allowed) {
        if (!allowed.contains(state)) {
            throw new InvalidTransitionException(state.value());
        }
        if (expectedVersion != version) {
            throw new VersionConflictException(version, state.value());
        }
    }

    /** The contractor may answer only the approved version, while it is {@code sent}. */
    private int requireAnswerable(long expectedVersion, int proposalVersion) {
        requireCurrent(expectedVersion, Set.of(InterventionState.SENT));
        int approved = approvedProposalVersion.orElseThrow(
                () -> new IllegalStateException("a sent intervention has an approved version"));
        requireNamed(proposalVersion, approved);
        return approved;
    }

    /**
     * The request names the version it is about. With a current {@code
     * If-Match} it can only differ by a client mistake, so this is a field
     * error, not a conflict.
     */
    private static void requireNamed(int named, int expected) {
        if (named != expected) {
            throw ValidationException.of("proposalVersion", "not_current");
        }
    }

    private Intervention next(
            ContractorId contractor, InterventionState nextState, int current, Optional<Integer> approved,
            Optional<Integer> agreed, boolean contacted, Instant now) {
        return new Intervention(id, issueId, buildingId, contractor, nextState, version + 1, current, approved, agreed,
                contacted, cancelReason, cancelNote, projectedIssueVersion, projectionAppliedAt, createdBy, createdAt,
                now);
    }
}
