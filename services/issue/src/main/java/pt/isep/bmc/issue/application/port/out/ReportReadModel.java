package pt.isep.bmc.issue.application.port.out;

import java.util.Optional;
import pt.isep.bmc.issue.application.query.ReportDetails;
import pt.isep.bmc.issue.application.query.ReportListCriteria;
import pt.isep.bmc.issue.application.query.ReportPage;
import pt.isep.bmc.issue.domain.report.ReportId;

/** Query side for reports: projections joined with the issue's state. */
public interface ReportReadModel {

    Optional<ReportDetails> findDetails(ReportId id);

    ReportPage list(ReportListCriteria criteria);
}
