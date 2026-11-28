package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/** Body of {@code POST /interventions/{id}/approval} (annex A 1.3): the version being approved. */
public record ApproveProposalRequest(Integer proposalVersion) {
}
