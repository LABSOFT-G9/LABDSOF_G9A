package pt.isep.bmc.issue.application.port.in;

import pt.isep.bmc.issue.application.query.ReportDetails;
import pt.isep.bmc.issue.domain.membership.UserId;
import pt.isep.bmc.issue.domain.report.ReportId;

/** {@code GET /reports/{id}}: the author or a building administrator. */
public interface GetReportQuery {

    ReportDetails get(UserId actor, ReportId reportId);
}
