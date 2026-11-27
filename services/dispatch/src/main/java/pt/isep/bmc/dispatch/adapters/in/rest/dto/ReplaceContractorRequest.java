package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/** Body of {@code PATCH /interventions/{id}}: the contractor to select instead. */
public record ReplaceContractorRequest(String contractorId) {
}
