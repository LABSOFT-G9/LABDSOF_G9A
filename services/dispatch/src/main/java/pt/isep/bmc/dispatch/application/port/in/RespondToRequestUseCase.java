package pt.isep.bmc.dispatch.application.port.in;

import pt.isep.bmc.dispatch.application.command.RespondCommand;
import pt.isep.bmc.dispatch.application.command.ResponseRecorded;

/** The assigned contractor accepts, declines or counterproposes the approved version (US-07). */
public interface RespondToRequestUseCase {

    ResponseRecorded respond(RespondCommand command);
}
