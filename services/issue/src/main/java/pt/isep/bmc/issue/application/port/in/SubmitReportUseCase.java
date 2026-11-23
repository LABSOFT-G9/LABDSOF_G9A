package pt.isep.bmc.issue.application.port.in;

import pt.isep.bmc.issue.application.command.ReportReceipt;
import pt.isep.bmc.issue.application.command.SubmitReportCommand;

/** {@code POST /reports} (technical design 4.2). */
public interface SubmitReportUseCase {

    ReportReceipt submit(SubmitReportCommand command);
}
