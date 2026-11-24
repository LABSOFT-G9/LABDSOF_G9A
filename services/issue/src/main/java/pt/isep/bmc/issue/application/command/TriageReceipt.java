package pt.isep.bmc.issue.application.command;

import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;

/**
 * The result of a stored triage decision.
 *
 * @param version  the issue's version after the decision ({@code ETag})
 * @param replayed true when an earlier result was returned for a repeated idempotency key
 */
public record TriageReceipt(
        IssueId issueId, IssueState state, long version, IssueCategory category, IssueUrgency urgency, boolean replayed) {
}
