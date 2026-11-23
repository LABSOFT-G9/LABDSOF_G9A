package pt.isep.bmc.issue.adapters.in.rest.dto;

/**
 * {@code GET /reports/{id}}: the raw text, for the author or a building
 * administrator only. The author's identity is not returned. {@link #toString()}
 * redacts the text.
 */
public record ReportResponse(
        String reportId,
        String issueId,
        String buildingId,
        String areaId,
        String areaLabel,
        String text,
        String source,
        boolean authoredByMe,
        String submittedAt,
        IssueStatus issue) {

    /** Current status of the report's issue. */
    public record IssueStatus(String state, long version) {
    }

    @Override
    public String toString() {
        return "ReportResponse[reportId=" + reportId + ", text=redacted]";
    }
}
