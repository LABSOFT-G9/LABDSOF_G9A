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
                INITIAL_VERSION, 1, Optional.empty(), Optional.empty(), false, projectedIssueVersion,
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
                contacted, projectedIssueVersion, projectionAppliedAt, createdBy, createdAt, now);
    }
}
