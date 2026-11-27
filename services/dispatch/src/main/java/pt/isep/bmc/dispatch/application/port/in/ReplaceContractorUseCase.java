package pt.isep.bmc.dispatch.application.port.in;

import pt.isep.bmc.dispatch.application.command.ContractorReplaced;
import pt.isep.bmc.dispatch.application.command.ReplaceContractorCommand;

/** An administrator selects another contractor for a draft request (US-06). */
public interface ReplaceContractorUseCase {

    ContractorReplaced replace(ReplaceContractorCommand command);
}
