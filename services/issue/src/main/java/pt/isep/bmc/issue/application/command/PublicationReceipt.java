package pt.isep.bmc.issue.application.command;

import java.time.Instant;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.SummaryText;

/**
 * The result of a published summary.
 *
 * @param version  the issue's version after the publication ({@code ETag})
 * @param replayed true when an earlier result was returned for a repeated idempotency key
 */
public record PublicationReceipt(IssueId issueId, long version, SummaryText text, Instant publishedAt, boolean replayed) {
}
