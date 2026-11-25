package pt.isep.bmc.issue.adapters.out.persistence;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.issue.application.port.out.TriageDecisionRepository;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.TriageDecision;

@Repository
class JpaTriageDecisionRepository implements TriageDecisionRepository {

    private final EntityManager entityManager;

    JpaTriageDecisionRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void add(TriageDecision decision) {
        entityManager.persist(PersistenceMapper.toEntity(decision));
    }

    @Override
    public Optional<TriageDecision> find(IssueId issueId, long resultingVersion) {
        return Optional.ofNullable(entityManager.find(
                        TriageDecisionEntity.class, new TriageDecisionEntity.Key(issueId.toString(), resultingVersion)))
                .map(PersistenceMapper::toDomain);
    }
}
