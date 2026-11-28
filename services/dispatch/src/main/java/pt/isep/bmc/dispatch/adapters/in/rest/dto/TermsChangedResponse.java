package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/** {@code 200} of {@code POST /interventions/{id}/proposals}: {@code awaiting_approval} with the new version. */
public record TermsChangedResponse(String interventionId, String state, long version, int proposalVersion) {
}
