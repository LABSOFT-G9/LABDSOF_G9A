package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/** Body of {@code POST /interventions/{id}/cancellation}: a reason code and an optional note. */
public record CancellationRequest(String reasonCode, String note) {

    /** Never prints the note: Spring logs request bodies at DEBUG. */
    @Override
    public String toString() {
        return "CancellationRequest[reasonCode=" + reasonCode + "]";
    }
}
