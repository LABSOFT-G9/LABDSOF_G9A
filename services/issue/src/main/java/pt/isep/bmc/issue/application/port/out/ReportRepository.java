package pt.isep.bmc.issue.application.port.out;

import java.util.List;
import java.util.Optional;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.membership.UserId;
import pt.isep.bmc.issue.domain.report.Report;
import pt.isep.bmc.issue.domain.report.ReportId;

public interface ReportRepository {

    void add(Report report);

    Optional<Report> findById(ReportId id);

    /** Every report of the issue, oldest first. */
    List<Report> findByIssue(IssueId issueId);

    /** Whether the user authored a report of this issue. */
    boolean existsByIssueAndAuthor(IssueId issueId, UserId author);
}
