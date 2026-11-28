package pt.isep.bmc.dispatch.application.query;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.intervention.CancelReason;
import pt.isep.bmc.dispatch.domain.intervention.DeclineReason;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.intervention.Money;
import pt.isep.bmc.dispatch.domain.intervention.ResponseDecision;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * An intervention as an administrator of its building sees it: the selected
 * contractor, the current proposal version and every version with its
 * approval and the contractor's response (US-07 history). Never the report
 * text: the scope extract is the administrator's own text.
 *
 * @param proposal                the current proposal version
 * @param proposals               every version, oldest first
 * @param approvedProposalVersion the version sent and not superseded since
 * @param agreedProposalVersion   the version both parties agreed, for US-08
 * @param contractorContacted     whether a request was ever sent; the contractor sees the intervention from then on
 * @param cancelNote              the administrator's text with a cancellation
 * @param bookings                every booking, oldest first; the last one is the current booking (US-08)
 * @param completions             every completion report, oldest first (US-09)
 */
public record InterventionView(
        InterventionId interventionId,
        IssueId issueId,
        BuildingId buildingId,
        Contractor contractor,
        InterventionState state,
        long version,
        Proposal proposal,
        List<Proposal> proposals,
        Optional<Integer> approvedProposalVersion,
        Optional<Integer> agreedProposalVersion,
        boolean contractorContacted,
        Optional<CancelReason> cancelReason,
        Optional<String> cancelNote,
        List<BookingView> bookings,
        List<CompletionView> completions,
        Instant createdAt,
        Instant updatedAt) implements InterventionRead {

    public InterventionView {
        Objects.requireNonNull(interventionId, "interventionId");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(contractor, "contractor");
        Objects.requireNonNull(proposal, "proposal");
        proposals = List.copyOf(proposals);
        bookings = List.copyOf(bookings);
        completions = List.copyOf(completions);
    }

    /** The current booking: the latest one. */
    public Optional<BookingView> booking() {
        return bookings.isEmpty() ? Optional.empty() : Optional.of(bookings.getLast());
    }

    /** @param userId the contractor's login, used only to recognise the contractor; never shown */
    public record Contractor(ContractorId contractorId, String displayName, boolean synthetic, Optional<UserId> userId) {
    }

    /** @param approvedAt set once the administrator approved this version */
    public record Proposal(
            int proposalVersion,
            String authorRole,
            String scopeExtract,
            TimeWindow window,
            String terms,
            Optional<Money> proposedCost,
            Instant createdAt,
            Optional<Instant> approvedAt,
            Optional<Response> response) {

        @Override
        public String toString() {
            return "Proposal[proposalVersion=" + proposalVersion + "]";
        }
    }

    /** @param note the contractor's text with a decline, for the administrator */
    public record Response(
            ResponseDecision decision, Optional<DeclineReason> reasonCode, Optional<String> note,
            Optional<Integer> counterProposalVersion, Instant respondedAt) {

        @Override
        public String toString() {
            return "Response[decision=" + decision + "]";
        }
    }
}
