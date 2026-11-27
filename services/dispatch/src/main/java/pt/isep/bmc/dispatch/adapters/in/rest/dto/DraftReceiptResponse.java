package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/** {@code 201} of {@code POST /interventions} (technical design annex A 1.3), plus the selected contractor. */
public record DraftReceiptResponse(
        String interventionId, String state, long version, int proposalVersion, String contractorId) {
}
