package pt.isep.bmc.dispatch.adapters.in.rest.dto;

import java.util.List;

/**
 * One intervention as {@code GET /interventions/{id}} and {@code GET
 * /interventions?issueId=} show it to an administrator of its building.
 *
 * @param proposal  the current proposal version
 * @param proposals every version, oldest first, with its approval and the contractor's response
 */
public record InterventionResponse(
        String interventionId,
        String issueId,
        String state,
        long version,
        Contractor contractor,
        Proposal proposal,
        List<Proposal> proposals,
        Integer approvedProposalVersion,
        Integer agreedProposalVersion,
        String createdAt,
        String updatedAt) {

    public record Contractor(String contractorId, String displayName, boolean synthetic) {
    }

    /**
     * @param proposedCost null when the version has none
     * @param approvedAt   null until the administrator approved this version
     * @param response     null until the contractor answered this version
     */
    public record Proposal(
            int proposalVersion,
            String authorRole,
            String scopeExtract,
            Window window,
            String terms,
            Cost proposedCost,
            String createdAt,
            String approvedAt,
            Response response) {
    }

    public record Window(String start, String end) {
    }

    public record Cost(String amount, String currency) {
    }

    /**
     * @param reasonCode             with a decline
     * @param note                   the contractor's text with a decline, if any
     * @param counterProposalVersion the version a counterproposal created
     */
    public record Response(
            String decision, String reasonCode, String note, Integer counterProposalVersion, String respondedAt) {
    }
}
