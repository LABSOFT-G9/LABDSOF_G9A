package pt.isep.bmc.issue.adapters.in.rest.dto;

import java.util.List;

/**
 * One issue, in {@code GET /issues} and {@code GET /issues/{id}}, already
 * projected for the caller. No report text and nobody's identity.
 *
 * @param areaLabel  the area the issue is about
 * @param version    sent back as {@code If-Match} on every change
 * @param triage     the administrator's current decision, or {@code null} while undecided
 * @param suggestion the unconfirmed suggestion, for administrators of the building only; {@code null}
 *                   for everyone else, while none was applied, and once a person decided
 * @param summary    the published summary, or {@code null} while the issue is not shared with the building
 * @param impact     how many residents confirmed the issue affects them; {@code null} while there is no summary
 * @param subscribed the caller's own notification choice; off by default
 * @param criticalCheck Issue's own critical check, for administrators of the building only; {@code null}
 *                      for everyone else
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
        Suggestion suggestion,
        Summary summary,
        Impact impact,
        boolean subscribed,
        CriticalCheck criticalCheck) {

    /**
     * Issue's own check for signs of danger in the report, independent of the
     * AI: no suggestion, retry or decision removes a signal.
     *
     * @param status       {@code pending} until the rule set has run on the report, then {@code completed}.
     *                     {@code pending} with no signals means "not checked", never "nothing found"
     * @param rulesVersion the rule set that completed the check; {@code null} while it is pending
     * @param signals      one per rule that matched, oldest first; empty when nothing matched
     */
    public record CriticalCheck(String status, String rulesVersion, List<Signal> signals) {

        /** @param code the rule's stable code, for example {@code gas_smell}; the client words it */
        public record Signal(String code, String raisedAt) {
        }
    }

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

    /** The administrator's reviewed text for the building: the only text about the issue that residents see. */
    public record Summary(String text, String publishedAt) {
    }

    /**
     * A count only, never who. Also the {@code 200} of {@code PUT} and {@code DELETE /issues/{id}/impact}.
     *
     * @param mine whether the caller confirmed
     */
    public record Impact(long count, boolean mine) {
    }

    /**
     * An unconfirmed suggestion: not the issue's category or urgency.
     *
     * @param origin       {@code ai}, {@code rules} or {@code fallback}
     * @param rationale    internal: {@code null} in lists
     * @param failureCode  why the AI result was not used; {@code null} unless the suggester sent one
     * @param suggestedAt  when the suggestion was recorded
     * @param rulesVersion {@code null} when the rules did not run
     * @param modelVersion {@code null} when no AI provider was called
     */
    public record Suggestion(
            String category,
            String urgency,
            String origin,
            String rationale,
            boolean needsReview,
            String failureCode,
            String suggestedAt,
            String rulesVersion,
            String modelVersion) {

        @Override
        public String toString() {
            return "Suggestion[category=" + category + ", urgency=" + urgency + ", origin=" + origin
                    + ", rationale=redacted]";
        }
    }
}
