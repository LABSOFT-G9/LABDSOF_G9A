package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/** Body of {@code POST /interventions/{id}/manual-confirmation} (US-08). */
public record ManualConfirmationRequest(CreateInterventionRequest.WindowBody window, String source, String reason) {

    /** Never prints the reason: Spring logs request bodies at DEBUG. */
    @Override
    public String toString() {
        return "ManualConfirmationRequest[source=" + source + "]";
    }
}
