package pt.isep.bmc.issue.adapters.out.persistence;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.issue.application.port.out.ReportRepository;
import pt.isep.bmc.issue.domain.report.Report;
import pt.isep.bmc.issue.domain.report.ReportId;

@Repository
class JpaReportRepository implements ReportRepository {

    private final EntityManager entityManager;
    private final ReportJpaRepository reports;

    JpaReportRepository(EntityManager entityManager, ReportJpaRepository reports) {
        this.entityManager = entityManager;
        this.reports = reports;
    }

    @Override
    public void add(Report report) {
        // persist, not save: the ID is assigned, so save() would SELECT first.
        entityManager.persist(PersistenceMapper.toEntity(report));
    }

    @Override
    public Optional<Report> findById(ReportId id) {
        return reports.findById(id.toString()).map(PersistenceMapper::toDomain);
    }
}
