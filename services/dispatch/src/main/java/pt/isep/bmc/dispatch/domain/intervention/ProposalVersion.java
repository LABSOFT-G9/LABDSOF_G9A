package pt.isep.bmc.dispatch.domain.intervention;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * One version of the terms of an intervention (technical design 2.2). US-06
 * creates version 1, written by the administrator; changed terms
 * (administrator) and counterproposals (contractor) add versions in US-07.
 * Raw report text is never copied here: the scope extract is written by the
 * administrator, and a counterproposal keeps it.
 *
 * @param number              1 for the first version
 * @param authorRole          {@link #AUTHOR_ADMINISTRATOR} or {@link #AUTHOR_CONTRACTOR}
 * @param costEstimate        optional, from a counterproposal
 * @param interventionVersion the intervention's version that this proposal created
 * @param approval            set once the administrator approved this version; an approval is never removed,
 *                            but a later version supersedes it
 */
public record ProposalVersion(
        InterventionId interventionId,
        int number,
        String authorRole,
        String scopeExtract,
        TimeWindow window,
        String terms,
        Optional<Money> costEstimate,
        long interventionVersion,
        UserId createdBy,
        Instant createdAt,
        Optional<Approval> approval) {

    public static final String AUTHOR_ADMINISTRATOR = "administrator";
    public static final String AUTHOR_CONTRACTOR = "contractor";

    public ProposalVersion {
        Objects.requireNonNull(interventionId, "interventionId");
        Objects.requireNonNull(authorRole, "authorRole");
        Objects.requireNonNull(scopeExtract, "scopeExtract");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(costEstimate, "costEstimate");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(approval, "approval");
        if (number < 1) {
            throw new IllegalArgumentException("proposal versions start at 1");
        }
        if (!AUTHOR_ADMINISTRATOR.equals(authorRole) && !AUTHOR_CONTRACTOR.equals(authorRole)) {
            throw new IllegalArgumentException("unknown author role");
        }
    }

    /** A version nobody has approved yet. */
    public static ProposalVersion proposed(
            InterventionId interventionId, int number, String authorRole, String scopeExtract, TimeWindow window,
            String terms, Optional<Money> costEstimate, long interventionVersion, UserId createdBy, Instant createdAt) {
        return new ProposalVersion(interventionId, number, authorRole, scopeExtract, window, terms, costEstimate,
                interventionVersion, createdBy, createdAt, Optional.empty());
    }

    public boolean byContractor() {
        return AUTHOR_CONTRACTOR.equals(authorRole);
    }

    /** This version, approved: what was approved, for which contractor, by whom and when (US-07). */
    public ProposalVersion approve(
            UserId approvedBy, ContractorId contractorId, long resultingInterventionVersion, Instant now) {
        if (approval.isPresent()) {
            throw new IllegalStateException("a proposal version is approved once");
        }
        return new ProposalVersion(interventionId, number, authorRole, scopeExtract, window, terms, costEstimate,
                interventionVersion, createdBy, createdAt,
                Optional.of(new Approval(approvedBy, contractorId, resultingInterventionVersion, now)));
    }

    /** Never prints the administrator's or the contractor's text. */
    @Override
    public String toString() {
        return "ProposalVersion[interventionId=" + interventionId + ", number=" + number + "]";
    }

    /**
     * @param contractorId        the contractor the approved version was sent to
     * @param interventionVersion the intervention's version after the approval
     */
    public record Approval(UserId approvedBy, ContractorId contractorId, long interventionVersion, Instant approvedAt) {

        public Approval {
            Objects.requireNonNull(approvedBy, "approvedBy");
            Objects.requireNonNull(contractorId, "contractorId");
            Objects.requireNonNull(approvedAt, "approvedAt");
        }
    }
}
