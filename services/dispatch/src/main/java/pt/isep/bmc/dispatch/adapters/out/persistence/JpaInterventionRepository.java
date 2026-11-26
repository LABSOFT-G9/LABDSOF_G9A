package pt.isep.bmc.dispatch.adapters.out.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.dispatch.application.port.out.InterventionRepository;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.intervention.ProposalVersion;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/** Interventions through JPA entities (ADR-002); mapping to and from the domain happens here. */
@Repository
class JpaInterventionRepository implements InterventionRepository {

    private static final List<String> TERMINAL = Arrays.stream(InterventionState.values())
            .filter(InterventionState::isTerminal)
            .map(InterventionState::value)
            .toList();

    private final EntityManager entityManager;

    JpaInterventionRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void add(Intervention intervention, ProposalVersion firstProposal) {
        InterventionEntity entity = new InterventionEntity();
        entity.id = intervention.id().toString();
        entity.issueId = intervention.issueId().toString();
        entity.buildingId = intervention.buildingId().toString();
        entity.projectedIssueVersion = intervention.projectedIssueVersion();
        entity.projectionAppliedAt = Timestamps.toColumn(intervention.projectionAppliedAt());
        entity.createdBy = intervention.createdBy().value();
        entity.createdAt = Timestamps.toColumn(intervention.createdAt());
        copyChangeableFields(intervention, entity);
        entityManager.persist(entity);

        ProposalVersionEntity proposal = new ProposalVersionEntity();
        proposal.interventionId = firstProposal.interventionId().toString();
        proposal.number = firstProposal.number();
        proposal.authorRole = firstProposal.authorRole();
        proposal.scopeExtract = firstProposal.scopeExtract();
        proposal.windowStart = Timestamps.toColumn(firstProposal.window().start());
        proposal.windowEnd = Timestamps.toColumn(firstProposal.window().end());
        proposal.terms = firstProposal.terms();
        proposal.createdBy = firstProposal.createdBy().value();
        proposal.createdAt = Timestamps.toColumn(firstProposal.createdAt());
        entityManager.persist(proposal);
        // Write now, so the unique key on the issue's active intervention is checked inside this call.
        entityManager.flush();
    }

    @Override
    public Optional<Intervention> find(InterventionId id) {
        return Optional.ofNullable(entityManager.find(InterventionEntity.class, id.toString()))
                .map(JpaInterventionRepository::toDomain);
    }

    @Override
    public Optional<Intervention> findForUpdate(InterventionId id) {
        InterventionEntity entity = entityManager.find(InterventionEntity.class, id.toString());
        if (entity == null) {
            return Optional.empty();
        }
        // SELECT ... FOR UPDATE, held until the transaction ends. A locking find on an entity this
        // transaction already loaded only locks the row and keeps the old fields, so the version
        // check would run on what was read before the lock. Refresh reads the row again under it.
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(toDomain(entity));
    }

    @Override
    public Optional<InterventionId> findActiveForIssue(IssueId issueId) {
        return entityManager.createQuery(
                        "SELECT i.id FROM InterventionEntity i WHERE i.issueId = :issue AND i.state NOT IN :terminal",
                        String.class)
                .setParameter("issue", issueId.toString())
                .setParameter("terminal", TERMINAL)
                .getResultStream()
                .findFirst()
                .map(id -> new InterventionId(UUID.fromString(id)));
    }

    @Override
    public void update(Intervention intervention) {
        InterventionEntity entity = entityManager.find(InterventionEntity.class, intervention.id().toString());
        if (entity == null) {
            throw new IllegalStateException("intervention to update does not exist");
        }
        copyChangeableFields(intervention, entity);
    }

    /** Only what a command may change; identity, issue, building, snapshot and creation never do. */
    private static void copyChangeableFields(Intervention intervention, InterventionEntity entity) {
        entity.contractorId = intervention.contractorId().toString();
        entity.state = intervention.state().value();
        entity.version = intervention.version();
        entity.currentProposalVersion = intervention.currentProposalVersion();
        entity.updatedAt = Timestamps.toColumn(intervention.updatedAt());
    }

    private static Intervention toDomain(InterventionEntity entity) {
        return new Intervention(
                new InterventionId(UUID.fromString(entity.id)),
                new IssueId(UUID.fromString(entity.issueId)),
                new BuildingId(UUID.fromString(entity.buildingId)),
                new ContractorId(UUID.fromString(entity.contractorId)),
                InterventionState.fromValue(entity.state),
                entity.version,
                entity.currentProposalVersion,
                entity.projectedIssueVersion,
                Timestamps.fromColumn(entity.projectionAppliedAt),
                new UserId(entity.createdBy),
                Timestamps.fromColumn(entity.createdAt),
                Timestamps.fromColumn(entity.updatedAt));
    }
}
