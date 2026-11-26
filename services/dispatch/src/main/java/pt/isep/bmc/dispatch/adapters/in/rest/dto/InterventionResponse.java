package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/** One intervention as {@code GET /interventions/{id}} and {@code GET /interventions?issueId=} show it. */
public record InterventionResponse(
        String interventionId,
        String issueId,
        String state,
        long version,
        Contractor contractor,
        Proposal proposal,
        String createdAt,
        String updatedAt) {

    public record Contractor(String contractorId, String displayName, boolean synthetic) {
    }

    public record Proposal(
            int proposalVersion, String authorRole, String scopeExtract, Window window, String terms, String createdAt) {
    }

    public record Window(String start, String end) {
    }
}
