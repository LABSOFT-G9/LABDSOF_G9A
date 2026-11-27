package pt.isep.bmc.dispatch.application.query;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.dispatch.domain.contractor.EligibleContractor;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/**
 * The answer to "which contractors may I select for this issue?" (US-06):
 * the eligible contractors in the documented order, and the criteria used,
 * so an empty list can be explained.
 *
 * @param issueState   the issue's state in Dispatch's projection
 * @param dispatchable whether an intervention may be created for the issue now
 * @param category     the issue's decided category; empty while undecided, and then nobody is eligible
 */
public record EligibleContractors(
        IssueId issueId,
        String issueState,
        boolean dispatchable,
        Optional<String> category,
        List<EligibleContractor> items) {

    public EligibleContractors {
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(category, "category");
        items = List.copyOf(items);
    }
}
