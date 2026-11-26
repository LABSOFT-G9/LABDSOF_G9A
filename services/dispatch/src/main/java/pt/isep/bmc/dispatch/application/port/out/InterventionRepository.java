package pt.isep.bmc.dispatch.application.port.out;

import java.util.Optional;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.ProposalVersion;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/** Interventions and their proposal versions (technical design 2.2). */
public interface InterventionRepository {

    void add(Intervention intervention, ProposalVersion firstProposal);

    Optional<Intervention> find(InterventionId id);

    /** Locks the row until the transaction ends. */
    Optional<Intervention> findForUpdate(InterventionId id);

    /** The issue's intervention in a non-terminal state, if any: there is at most one. */
    Optional<InterventionId> findActiveForIssue(IssueId issueId);

    /** Stores a changed intervention; identity, issue and building never change. */
    void update(Intervention intervention);
}
