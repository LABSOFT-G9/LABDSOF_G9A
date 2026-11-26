package pt.isep.bmc.dispatch.application.port.in;

import pt.isep.bmc.dispatch.application.command.CreateInterventionCommand;
import pt.isep.bmc.dispatch.application.command.DraftReceipt;

/** An administrator selects a contractor and saves a draft request (US-06). Sends nothing. */
public interface CreateInterventionUseCase {

    DraftReceipt create(CreateInterventionCommand command);
}
