package pt.isep.bmc.dispatch.application.query;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.dispatch.domain.booking.BookingSource;
import pt.isep.bmc.dispatch.domain.booking.BookingState;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;

/**
 * A request as the assigned contractor sees it (US-07, technical design
 * 4.2): only after it was sent, and only what the work needs. The proposal
 * versions are the ones sent to the contractor and the contractor's own
 * counterproposals; changed terms the administrator has not approved stay
 * hidden. No issue ID, report, building administrator or resident appears.
 *
 * @param awaitingResponseTo the version the contractor can answer now, while the request is {@code sent}
 * @param agreedProposalVersion the version both parties agreed
 */
public record AssignmentView(
        InterventionId interventionId,
        InterventionState state,
        long version,
        InterventionView.Contractor contractor,
        Work work,
        Optional<Integer> awaitingResponseTo,
        Optional<Integer> agreedProposalVersion,
        List<InterventionView.Proposal> proposals,
        Optional<Booking> booking,
        List<CompletionView> completions,
        Instant updatedAt) implements InterventionRead {

    public AssignmentView {
        Objects.requireNonNull(interventionId, "interventionId");
        Objects.requireNonNull(work, "work");
        proposals = List.copyOf(proposals);
        completions = List.copyOf(completions);
    }

    /** The current booking as the contractor needs it: its state, source and window. */
    public record Booking(BookingState state, Optional<BookingSource> source, TimeWindow window) {
    }

    /** What the issue projection says about the work: category, urgency and where in the building. */
    public record Work(Optional<String> category, Optional<String> urgency, String areaLabel) {
    }

    /** The contractor's view of an intervention: versions sent to them or written by them. */
    static AssignmentView of(InterventionView view, Work work) {
        List<InterventionView.Proposal> visible = view.proposals().stream()
                .filter(p -> p.approvedAt().isPresent() || "contractor".equals(p.authorRole()))
                .toList();
        Optional<Integer> awaiting = view.state() == InterventionState.SENT
                ? view.approvedProposalVersion()
                : Optional.empty();
        return new AssignmentView(view.interventionId(), view.state(), view.version(),
                view.contractor(), work, awaiting,
                view.agreedProposalVersion(), visible,
                view.booking().map(b -> new Booking(b.state(), b.source(), b.bookedWindow())),
                view.completions().stream().map(CompletionView::forContractor).toList(), view.updatedAt());
    }
}
