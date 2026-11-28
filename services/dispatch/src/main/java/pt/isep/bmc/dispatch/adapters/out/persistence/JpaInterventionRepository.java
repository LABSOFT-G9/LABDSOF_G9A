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
import pt.isep.bmc.dispatch.domain.intervention.CancelReason;
import pt.isep.bmc.dispatch.domain.intervention.ContractorResponse;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.intervention.Money;
import pt.isep.bmc.dispatch.domain.intervention.ProposalVersion;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;
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
        addProposal(firstProposal);
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

    @Override
    public Optional<ProposalVersion> findProposal(InterventionId id, int number) {
        ProposalVersionEntity.Key key = new ProposalVersionEntity.Key();
        key.interventionId = id.toString();
        key.number = number;
        return Optional.ofNullable(entityManager.find(ProposalVersionEntity.class, key))
                .map(JpaInterventionRepository::toDomain);
    }

    @Override
    public Optional<ProposalVersion> findProposalForUpdate(InterventionId id, int number) {
        ProposalVersionEntity.Key key = new ProposalVersionEntity.Key();
        key.interventionId = id.toString();
        key.number = number;
        // SELECT ... FOR SHARE: a locking read sees rows committed after this transaction's snapshot.
        return Optional.ofNullable(entityManager.find(ProposalVersionEntity.class, key, LockModeType.PESSIMISTIC_READ))
                .map(JpaInterventionRepository::toDomain);
    }

    @Override
    public Optional<ProposalVersion> findProposalCreatedAt(InterventionId id, long interventionVersion) {
        return entityManager.createQuery("SELECT p FROM ProposalVersionEntity p"
                        + " WHERE p.interventionId = :id AND p.interventionVersion = :version",
                        ProposalVersionEntity.class)
                .setParameter("id", id.toString())
                .setParameter("version", interventionVersion)
                .getResultStream()
                .findFirst()
                .map(JpaInterventionRepository::toDomain);
    }

    @Override
    public void addProposal(ProposalVersion proposal) {
        ProposalVersionEntity entity = new ProposalVersionEntity();
        entity.interventionId = proposal.interventionId().toString();
        entity.number = proposal.number();
        entity.authorRole = proposal.authorRole();
        entity.scopeExtract = proposal.scopeExtract();
        entity.windowStart = Timestamps.toColumn(proposal.window().start());
        entity.windowEnd = Timestamps.toColumn(proposal.window().end());
        entity.terms = proposal.terms();
        entity.costAmount = proposal.costEstimate().map(Money::amount).orElse(null);
        entity.costCurrency = proposal.costEstimate().map(Money::currency).orElse(null);
        entity.interventionVersion = proposal.interventionVersion();
        entity.createdBy = proposal.createdBy().value();
        entity.createdAt = Timestamps.toColumn(proposal.createdAt());
        copyApproval(proposal, entity);
        entityManager.persist(entity);
    }

    @Override
    public void recordApproval(ProposalVersion approved) {
        ProposalVersionEntity.Key key = new ProposalVersionEntity.Key();
        key.interventionId = approved.interventionId().toString();
        key.number = approved.number();
        ProposalVersionEntity entity = entityManager.find(ProposalVersionEntity.class, key);
        if (entity == null || entity.approvedAt != null) {
            throw new IllegalStateException("only an existing, unapproved proposal version can be approved");
        }
        copyApproval(approved, entity);
    }

    @Override
    public void addResponse(ContractorResponse response) {
        ContractorResponseEntity entity = new ContractorResponseEntity();
        entity.interventionId = response.interventionId().toString();
        entity.proposalVersion = response.proposalVersion();
        entity.decision = response.decision().value();
        entity.reasonCode = response.reason().map(r -> r.value()).orElse(null);
        entity.note = response.note().orElse(null);
        entity.counterProposalVersion = response.counterProposalVersion().orElse(null);
        entity.interventionVersion = response.interventionVersion();
        entity.respondedBy = response.respondedBy().value();
        entity.respondedAt = Timestamps.toColumn(response.respondedAt());
        entityManager.persist(entity);
    }

    /** Only what a command may change; identity, issue, building, snapshot and creation never do. */
    private static void copyChangeableFields(Intervention intervention, InterventionEntity entity) {
        entity.contractorId = intervention.contractorId().toString();
        entity.state = intervention.state().value();
        entity.version = intervention.version();
        entity.currentProposalVersion = intervention.currentProposalVersion();
        entity.approvedProposalVersion = intervention.approvedProposalVersion().orElse(null);
        entity.agreedProposalVersion = intervention.agreedProposalVersion().orElse(null);
        entity.contractorContacted = intervention.contractorContacted();
        entity.cancelReasonCode = intervention.cancelReason().map(CancelReason::value).orElse(null);
        entity.cancelNote = intervention.cancelNote().orElse(null);
        entity.updatedAt = Timestamps.toColumn(intervention.updatedAt());
    }

    private static void copyApproval(ProposalVersion proposal, ProposalVersionEntity entity) {
        proposal.approval().ifPresent(approval -> {
            entity.approvedBy = approval.approvedBy().value();
            entity.approvedContractorId = approval.contractorId().toString();
            entity.approvedInterventionVersion = approval.interventionVersion();
            entity.approvedAt = Timestamps.toColumn(approval.approvedAt());
        });
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
                Optional.ofNullable(entity.approvedProposalVersion),
                Optional.ofNullable(entity.agreedProposalVersion),
                entity.contractorContacted,
                Optional.ofNullable(entity.cancelReasonCode).flatMap(CancelReason::fromValue),
                Optional.ofNullable(entity.cancelNote),
                entity.projectedIssueVersion,
                Timestamps.fromColumn(entity.projectionAppliedAt),
                new UserId(entity.createdBy),
                Timestamps.fromColumn(entity.createdAt),
                Timestamps.fromColumn(entity.updatedAt));
    }

    private static ProposalVersion toDomain(ProposalVersionEntity entity) {
        Optional<Money> cost = entity.costAmount == null
                ? Optional.empty()
                : Optional.of(new Money(entity.costAmount, entity.costCurrency));
        Optional<ProposalVersion.Approval> approval = entity.approvedAt == null
                ? Optional.empty()
                : Optional.of(new ProposalVersion.Approval(new UserId(entity.approvedBy),
                        new ContractorId(UUID.fromString(entity.approvedContractorId)),
                        entity.approvedInterventionVersion, Timestamps.fromColumn(entity.approvedAt)));
        return new ProposalVersion(
                new InterventionId(UUID.fromString(entity.interventionId)),
                entity.number,
                entity.authorRole,
                entity.scopeExtract,
                new TimeWindow(Timestamps.fromColumn(entity.windowStart), Timestamps.fromColumn(entity.windowEnd)),
                entity.terms,
                cost,
                entity.interventionVersion,
                new UserId(entity.createdBy),
                Timestamps.fromColumn(entity.createdAt),
                approval);
    }
}
