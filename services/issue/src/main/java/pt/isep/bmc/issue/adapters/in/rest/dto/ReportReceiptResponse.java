package pt.isep.bmc.issue.adapters.in.rest.dto;

/** {@code 201} body of {@code POST /reports} (contract annex 1.1). */
public record ReportReceiptResponse(String reportId, String issueId, String state, long version, String submittedAt) {
}
