package pt.isep.bmc.issue.application.port.out;

import java.util.Optional;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueSummary;

/** Published summaries are appended, never changed: publishing again is a new summary. */
public interface SummaryRepository {

    void add(IssueSummary summary);

    /** The summary that produced this version of the issue, if any. */
    Optional<IssueSummary> find(IssueId issueId, long resultingVersion);
}
