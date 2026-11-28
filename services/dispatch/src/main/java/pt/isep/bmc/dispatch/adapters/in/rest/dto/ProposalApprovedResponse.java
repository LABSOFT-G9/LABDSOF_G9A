package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/** {@code 200} of {@code POST /interventions/{id}/approval}: {@code sent}, or {@code scheduling_pending}. */
public record ProposalApprovedResponse(String interventionId, String state, long version, int approvedProposalVersion) {
}
