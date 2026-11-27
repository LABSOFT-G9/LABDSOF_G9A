package pt.isep.bmc.dispatch.domain.intervention;

import java.time.Instant;
import java.util.Objects;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;
import pt.isep.bmc.dispatch.domain.shared.VersionConflictException;

/**
 * An intervention for an issue (technical design 2.2 and 3.2). Immutable:
 * every change returns the next version.
 *
 * @param buildingId            snapshot of the issue's building, taken from the issue projection at creation
 *                              (technical design 4.3 step 4)
 * @param projectedIssueVersion the issue version of the projection row used for the check at creation
 * @param projectionAppliedAt   when that projection row was written
 */
public record Intervention(
        InterventionId id,
        IssueId issueId,
        BuildingId buildingId,
        ContractorId contractorId,
        InterventionState state,
        long version,
        int currentProposalVersion,
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
        Objects.requireNonNull(projectionAppliedAt, "projectionAppliedAt");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < INITIAL_VERSION) {
            throw new IllegalArgumentException("intervention version must be at least 1");
        }
    }

    /**
     * The administrator selects another contractor for the draft (US-06).
     * Allowed only in {@code awaiting_approval}, before anything is sent. The
     * state is checked before the version, as in technical design 3.3: a
     * draft that has moved on answers {@code INVALID_TRANSITION}.
     *
     * @param expectedVersion the version the administrator was looking at ({@code If-Match})
     * @throws InvalidTransitionException if the intervention is no longer a draft
     * @throws VersionConflictException   if it changed since the administrator read it
     */
    public Intervention replaceContractor(long expectedVersion, ContractorId contractor, Instant now) {
        Objects.requireNonNull(contractor, "contractor");
        if (state != InterventionState.AWAITING_APPROVAL) {
            throw new InvalidTransitionException(state.value());
        }
        if (expectedVersion != version) {
            throw new VersionConflictException(version, state.value());
        }
        return new Intervention(id, issueId, buildingId, contractor, state, version + 1, currentProposalVersion,
                projectedIssueVersion, projectionAppliedAt, createdBy, createdAt, now);
    }
}
