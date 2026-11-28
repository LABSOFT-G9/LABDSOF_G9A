package pt.isep.bmc.issue.adapters.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to report rows; used only by {@link JpaReportRepository}. */
interface ReportJpaRepository extends JpaRepository<ReportEntity, String> {

    boolean existsByIssueIdAndAuthorUserId(String issueId, String authorUserId);

    List<ReportEntity> findByIssueIdOrderBySubmittedAtAscIdAsc(String issueId);
}
