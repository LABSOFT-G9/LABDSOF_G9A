package pt.isep.bmc.issue.adapters.in.rest.dto;

/**
 * One issue, in {@code GET /issues} and {@code GET /issues/{id}}, already
 * projected for the caller. No report text and nobody's identity.
 *
 * @param areaLabel  the area the issue is about
 * @param version    sent back as {@code If-Match} on every change
 * @param triage     the administrator's current decision, or {@code null} while undecided
 * @param suggestion the latest unconfirmed suggestion, administrators only. Always
 *                   {@code null} until suggestions are recorded (US-03-03)
 */
public record IssueResponse(
        String issueId,
        String buildingId,
        String buildingName,
        String areaLabel,
        String state,
        long version,
        String createdAt,
        Triage triage,
        Suggestion suggestion) {

    /**
     * @param reason         internal: {@code null} for residents and in lists
     * @param decisionSource always {@code human}
     */
    public record Triage(String category, String urgency, String reason, String decidedAt, String decisionSource) {

        @Override
        public String toString() {
            return "Triage[category=" + category + ", urgency=" + urgency + ", reason=redacted]";
        }
    }

    /** An unconfirmed suggestion: {@code origin} is {@code ai}, {@code rules} or {@code fallback}. */
    public record Suggestion(String category, String urgency, String origin) {
    }
}
