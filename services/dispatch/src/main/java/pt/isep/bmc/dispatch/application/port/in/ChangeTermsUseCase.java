package pt.isep.bmc.dispatch.application.port.in;

import pt.isep.bmc.dispatch.application.command.ChangeTermsCommand;
import pt.isep.bmc.dispatch.application.command.TermsChanged;

/** An administrator writes a new proposal version before the contractor's answer (US-07). */
public interface ChangeTermsUseCase {

    TermsChanged changeTerms(ChangeTermsCommand command);
}
