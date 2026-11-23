package pt.isep.bmc.issue.application.command;

import java.time.Instant;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.report.ReportId;

/**
 * What {@code POST /reports} returns once the report is durably stored
 * (contract annex 1.1). A replay returns the same receipt.
 *
 * @param replayed true when this is the stored result of an earlier request
 */
public record ReportReceipt(
        ReportId reportId, IssueId issueId, IssueState state, long version, Instant submittedAt, boolean replayed) {
}
