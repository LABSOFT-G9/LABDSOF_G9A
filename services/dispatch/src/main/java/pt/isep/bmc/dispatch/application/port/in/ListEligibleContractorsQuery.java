package pt.isep.bmc.dispatch.application.port.in;

import pt.isep.bmc.dispatch.application.query.EligibleContractors;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/** The contractors an administrator of the issue's building may select (US-06). */
public interface ListEligibleContractorsQuery {

    EligibleContractors list(UserId actor, IssueId issueId);
}
