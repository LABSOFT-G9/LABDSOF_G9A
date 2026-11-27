package pt.isep.bmc.dispatch.domain.intervention;

import java.util.Objects;

/** What a new draft request stores, in one transaction: the intervention and proposal version 1. */
public record DraftOutcome(Intervention intervention, ProposalVersion proposal) {

    public DraftOutcome {
        Objects.requireNonNull(intervention, "intervention");
        Objects.requireNonNull(proposal, "proposal");
    }
}
