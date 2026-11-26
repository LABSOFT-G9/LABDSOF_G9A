package pt.isep.bmc.dispatch.domain.intervention;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * The assigned contractor's answer to one approved proposal version
 * (technical design 2.2, "Contractor response"). At most one per version.
 *
 * @param proposalVersion        the version answered
 * @param reason                 present for a decline only
 * @param note                   optional text for the administrator with a decline; never in an event or log
 * @param counterProposalVersion the version a counterproposal created
 * @param interventionVersion    the intervention's version after the response
 */
public record ContractorResponse(
        InterventionId interventionId,
        int proposalVersion,
        ResponseDecision decision,
        Optional<DeclineReason> reason,
        Optional<String> note,
        Optional<Integer> counterProposalVersion,
        long interventionVersion,
        UserId respondedBy,
        Instant respondedAt) {

    public ContractorResponse {
        Objects.requireNonNull(interventionId, "interventionId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(note, "note");
        Objects.requireNonNull(counterProposalVersion, "counterProposalVersion");
        Objects.requireNonNull(respondedBy, "respondedBy");
        Objects.requireNonNull(respondedAt, "respondedAt");
        if (reason.isPresent() != (decision == ResponseDecision.DECLINE)) {
            throw new IllegalArgumentException("a reason goes with a decline only");
        }
        if (counterProposalVersion.isPresent() != (decision == ResponseDecision.COUNTERPROPOSE)) {
            throw new IllegalArgumentException("a new version goes with a counterproposal only");
        }
    }

    /** Never prints the contractor's note. */
    @Override
    public String toString() {
        return "ContractorResponse[interventionId=" + interventionId + ", proposalVersion=" + proposalVersion
                + ", decision=" + decision + "]";
    }
}
