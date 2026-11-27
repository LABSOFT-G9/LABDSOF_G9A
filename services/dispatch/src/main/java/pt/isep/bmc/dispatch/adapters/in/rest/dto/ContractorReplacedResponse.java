package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/** {@code 200} of {@code PATCH /interventions/{id}}. */
public record ContractorReplacedResponse(String interventionId, String state, long version, String contractorId) {
}
