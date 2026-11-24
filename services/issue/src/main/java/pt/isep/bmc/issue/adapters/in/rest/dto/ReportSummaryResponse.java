package pt.isep.bmc.issue.adapters.in.rest.dto;

/** One row of {@code GET /reports}. No text. */
public record ReportSummaryResponse(
        String reportId,
        String issueId,
        String buildingId,
        String areaId,
        String areaLabel,
        String source,
        boolean authoredByMe,
        String submittedAt,
        ReportResponse.IssueStatus issue) {
}
