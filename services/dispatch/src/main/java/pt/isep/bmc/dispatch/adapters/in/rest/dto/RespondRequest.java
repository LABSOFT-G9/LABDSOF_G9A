package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/**
 * Body of {@code POST /interventions/{id}/response} (annex A 1.4). {@code
 * reasonCode} and {@code note} go with {@code decline}; {@code window},
 * {@code terms} and {@code proposedCost} with {@code counterpropose}.
 */
public record RespondRequest(
        Integer proposalVersion,
        String decision,
        String reasonCode,
        String note,
        CreateInterventionRequest.WindowBody window,
        String terms,
        Cost proposedCost) {

    /** @param amount a decimal string such as {@code "85.00"}, so nothing is rounded */
    public record Cost(String amount, String currency) {
    }

    /** Never prints free text: Spring logs request bodies at DEBUG. */
    @Override
    public String toString() {
        return "RespondRequest[proposalVersion=" + proposalVersion + ", decision=" + decision + "]";
    }
}
