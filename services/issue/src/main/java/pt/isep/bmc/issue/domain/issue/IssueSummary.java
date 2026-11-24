package pt.isep.bmc.issue.domain.issue;

import java.time.Instant;
import java.util.Objects;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * A summary of an issue, written and published by an administrator for the
 * residents of the building (technical design 2.1, US-04). One summary per
 * issue version; publishing again adds a new one and earlier ones are kept.
 *
 * @param resultingVersion the issue's version once this summary was published
 */
public record IssueSummary(
        SummaryId id, IssueId issueId, SummaryText text, UserId publishedBy, long resultingVersion, Instant publishedAt) {

    public IssueSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(publishedBy, "publishedBy");
        Objects.requireNonNull(publishedAt, "publishedAt");
    }

    /** Never prints who published. */
    @Override
    public String toString() {
        return "IssueSummary[id=" + id + ", issueId=" + issueId + ", resultingVersion=" + resultingVersion + "]";
    }
}
