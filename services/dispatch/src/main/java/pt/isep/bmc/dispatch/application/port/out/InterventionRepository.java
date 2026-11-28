package pt.isep.bmc.dispatch.application.port.out;

import java.util.Optional;
import pt.isep.bmc.dispatch.domain.intervention.ContractorResponse;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.ProposalVersion;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/** Interventions, their proposal versions and the contractor's responses (technical design 2.2). */
public interface InterventionRepository {

    void add(Intervention intervention, ProposalVersion firstProposal);

    Optional<Intervention> find(InterventionId id);

    /** Locks the row until the transaction ends, and reads it again under the lock. */
    Optional<Intervention> findForUpdate(InterventionId id);

    /** The issue's intervention in a non-terminal state, if any: there is at most one. */
    Optional<InterventionId> findActiveForIssue(IssueId issueId);

    void update(Intervention intervention);

    Optional<ProposalVersion> findProposal(InterventionId id, int number);

    /**
     * The proposal version, read under a shared lock so that it is the latest
     * committed one. Commands call it while they hold the intervention's lock,
     * after another transaction may have added the version.
     */
    Optional<ProposalVersion> findProposalForUpdate(InterventionId id, int number);

    /** The proposal version that the intervention's version {@code interventionVersion} created. */
    Optional<ProposalVersion> findProposalCreatedAt(InterventionId id, long interventionVersion);

    void addProposal(ProposalVersion proposal);

    /** Stores the approval of an existing version; nothing else of a version ever changes. */
    void recordApproval(ProposalVersion approved);

    void addResponse(ContractorResponse response);
}
