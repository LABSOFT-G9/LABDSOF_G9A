package pt.isep.bmc.issue.adapters.in.rest.dto;

/** Body of {@code POST /issues/{id}/triage}. The reason is 1 to 500 characters. */
public record TriageRequest(String category, String urgency, String reason) {

    /** The reason may quote a private report: never printed. */
    @Override
    public String toString() {
        return "TriageRequest[category=" + category + ", urgency=" + urgency + ", reason=redacted]";
    }
}
