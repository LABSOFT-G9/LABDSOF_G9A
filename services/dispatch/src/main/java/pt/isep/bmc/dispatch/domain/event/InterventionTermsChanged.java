package pt.isep.bmc.dispatch.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * {@code bmc.dispatch.v1.InterventionTermsChanged}: an administrator wrote
 * new terms, so a new version needs approval. From {@code sent} the request
 * the contractor received is withdrawn; over a counterproposal, the
 * contractor's version is superseded. No scope, terms or cost text.
 *
 * @param supersededAuthor {@code administrator} or {@code contractor}
 * @param contractorUserId the contractor to tell, when the request had been
 *                         sent to them or they wrote the superseded version
 */
public record InterventionTermsChanged(
        UUID aggregateId,
        long aggregateVersion,
        BuildingId buildingId,
        Instant occurredAt,
        IssueId issueId,
        int proposalVersion,
        int supersededProposalVersion,
        String supersededAuthor,
        InterventionState previousState,
        Optional<UserId> contractorUserId,
        InterventionState interventionState) implements DomainEvent {

    public InterventionTermsChanged {
        Objects.requireNonNull(supersededAuthor, "supersededAuthor");
        Objects.requireNonNull(previousState, "previousState");
        Objects.requireNonNull(contractorUserId, "contractorUserId");
    }

    @Override
    public String type() {
        return "InterventionTermsChanged";
    }
}
