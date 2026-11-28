package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/**
 * {@code 200} of {@code POST /interventions/{id}/response}.
 *
 * @param proposalVersion the current proposal version: the new one after a counterproposal
 */
public record ResponseRecordedResponse(String interventionId, String state, long version, int proposalVersion) {
}
