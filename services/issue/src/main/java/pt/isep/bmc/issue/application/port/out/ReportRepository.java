package pt.isep.bmc.issue.application.port.out;

import java.util.Optional;
import pt.isep.bmc.issue.domain.report.Report;
import pt.isep.bmc.issue.domain.report.ReportId;

public interface ReportRepository {

    void add(Report report);

    Optional<Report> findById(ReportId id);
}
