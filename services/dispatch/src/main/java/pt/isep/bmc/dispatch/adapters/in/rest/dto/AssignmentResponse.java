package pt.isep.bmc.dispatch.adapters.in.rest.dto;

import java.util.List;

/**
 * A request as the assigned contractor sees it ({@code GET /interventions/{id}}
 * and {@code GET /interventions?assigned=me}): the work, the versions sent to
 * them and their own counterproposals. No issue ID, report or resident.
 *
 * @param awaitingResponseTo the version the contractor can answer now; null unless {@code sent}
 */
public record AssignmentResponse(
        String interventionId,
        String state,
        long version,
        InterventionResponse.Contractor contractor,
        Work work,
        Integer awaitingResponseTo,
        Integer agreedProposalVersion,
        List<InterventionResponse.Proposal> proposals,
        String updatedAt) {

    /** @param category and urgency null while undecided */
    public record Work(String category, String urgency, String areaLabel) {
    }
}
