package pt.isep.bmc.issue.domain.issue;

import java.time.Instant;
import java.util.Objects;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * An administrator's decision on an issue's category and urgency (technical
 * design 2.1). Always human: a suggestion is never a decision. One decision
 * per issue version; earlier ones are kept.
 *
 * @param resultingVersion the issue's version once this decision was applied
 */
public record TriageDecision(
        IssueId issueId,
        IssueCategory category,
        IssueUrgency urgency,
        TriageReason reason,
        UserId decidedBy,
        long resultingVersion,
        Instant decidedAt) {

    /** Wire value of {@code decisionSource}: only people decide. */
    public static final String SOURCE_HUMAN = "human";

    public TriageDecision {
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(urgency, "urgency");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedAt, "decidedAt");
    }

    /** Never prints who decided or why. */
    @Override
    public String toString() {
        return "TriageDecision[issueId=" + issueId + ", category=" + category.value() + ", urgency=" + urgency.value()
                + ", resultingVersion=" + resultingVersion + "]";
    }
}
