package pt.isep.bmc.issue.adapters.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to report rows; used only by {@link JpaReportRepository}. */
interface ReportJpaRepository extends JpaRepository<ReportEntity, String> {
}
