package pt.isep.bmc.dispatch.application.port.in;

import pt.isep.bmc.dispatch.application.command.ApproveProposalCommand;
import pt.isep.bmc.dispatch.application.command.ProposalApproved;

/** An administrator approves the current proposal version, which sends the request (US-07). */
public interface ApproveProposalUseCase {

    ProposalApproved approve(ApproveProposalCommand command);
}
