package pt.isep.bmc.dispatch.adapters.in.rest;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.AssignmentResponse;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.InterventionResponse;
import pt.isep.bmc.dispatch.application.query.AssignmentView;
import pt.isep.bmc.dispatch.application.query.InterventionView;
import pt.isep.bmc.dispatch.domain.intervention.DeclineReason;

/** Maps the query views to JSON: absent values become {@code null}, times are RFC 3339 in UTC. */
final class InterventionResponses {

    private InterventionResponses() {
    }

    static InterventionResponse administrator(InterventionView view) {
        return new InterventionResponse(
                view.interventionId().toString(),
                view.issueId().toString(),
                view.state().value(),
                view.version(),
                contractor(view.contractor()),
                proposal(view.proposal()),
                proposals(view.proposals()),
                view.approvedProposalVersion().orElse(null),
                view.agreedProposalVersion().orElse(null),
                time(view.createdAt()),
                time(view.updatedAt()));
    }

    static AssignmentResponse contractor(AssignmentView view) {
        AssignmentView.Work work = view.work();
        return new AssignmentResponse(
                view.interventionId().toString(),
                view.state().value(),
                view.version(),
                contractor(view.contractor()),
                new AssignmentResponse.Work(work.category().orElse(null), work.urgency().orElse(null),
                        work.areaLabel()),
                view.awaitingResponseTo().orElse(null),
                view.agreedProposalVersion().orElse(null),
                proposals(view.proposals()),
                time(view.updatedAt()));
    }

    private static InterventionResponse.Contractor contractor(InterventionView.Contractor contractor) {
        return new InterventionResponse.Contractor(contractor.contractorId().toString(), contractor.displayName(),
                contractor.synthetic());
    }

    private static List<InterventionResponse.Proposal> proposals(List<InterventionView.Proposal> proposals) {
        return proposals.stream().map(InterventionResponses::proposal).toList();
    }

    private static InterventionResponse.Proposal proposal(InterventionView.Proposal proposal) {
        return new InterventionResponse.Proposal(
                proposal.proposalVersion(),
                proposal.authorRole(),
                proposal.scopeExtract(),
                new InterventionResponse.Window(time(proposal.window().start()), time(proposal.window().end())),
                proposal.terms(),
                proposal.proposedCost()
                        .map(cost -> new InterventionResponse.Cost(cost.amountText(), cost.currency()))
                        .orElse(null),
                time(proposal.createdAt()),
                proposal.approvedAt().map(InterventionResponses::time).orElse(null),
                proposal.response()
                        .map(r -> new InterventionResponse.Response(
                                r.decision().value(),
                                r.reasonCode().map(DeclineReason::value).orElse(null),
                                r.note().orElse(null),
                                r.counterProposalVersion().orElse(null),
                                time(r.respondedAt())))
                        .orElse(null));
    }

    static String time(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}
