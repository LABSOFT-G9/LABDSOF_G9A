package pt.isep.bmc.issue.adapters.in.rest.dto;

/**
 * {@code 200} of {@code POST /issues/{id}/triage} (contract examples 1.2).
 *
 * @param version        the issue's new version; also in the {@code ETag} header
 * @param decisionSource always {@code human}: a suggestion is never a decision
 */
public record TriageReceiptResponse(
        String issueId, String state, long version, String category, String urgency, String decisionSource) {
}
