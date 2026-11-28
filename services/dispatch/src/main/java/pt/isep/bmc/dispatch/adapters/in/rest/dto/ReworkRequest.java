package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/** Body of {@code POST /interventions/{id}/rework}. */
public record ReworkRequest(String reason) {

    /** Never prints the reason: Spring logs request bodies at DEBUG. */
    @Override
    public String toString() {
        return "ReworkRequest[]";
    }
}
