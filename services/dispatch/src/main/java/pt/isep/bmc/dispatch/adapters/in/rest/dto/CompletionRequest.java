package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/** Body of {@code POST /interventions/{id}/completion}; {@code reason} only when an administrator reports it. */
public record CompletionRequest(String summary, String performedAt, String reason) {

    /** Never prints the summary or the reason: Spring logs request bodies at DEBUG. */
    @Override
    public String toString() {
        return "CompletionRequest[]";
    }
}
