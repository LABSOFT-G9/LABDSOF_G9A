package pt.isep.bmc.issue.application.port.in;

import pt.isep.bmc.issue.application.command.PublicationReceipt;
import pt.isep.bmc.issue.application.command.PublishSummaryCommand;

/** An administrator publishes or replaces the shared summary of an issue (US-04). */
public interface PublishSummaryUseCase {

    PublicationReceipt publish(PublishSummaryCommand command);
}
