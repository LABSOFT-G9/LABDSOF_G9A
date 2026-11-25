package pt.isep.bmc.issue.adapters.out.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.issue.application.port.out.IssueRepository;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;
import pt.isep.bmc.issue.domain.issue.SummaryId;

@Repository
class JpaIssueRepository implements IssueRepository {

    private final EntityManager entityManager;

    JpaIssueRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void add(Issue issue) {
        entityManager.persist(PersistenceMapper.toEntity(issue));
    }

    @Override
    public Optional<Issue> find(IssueId id) {
        return Optional.ofNullable(entityManager.find(IssueEntity.class, id.toString()))
                .map(PersistenceMapper::toDomain);
    }

    @Override
    public Optional<Issue> findForUpdate(IssueId id) {
        // SELECT ... FOR UPDATE: the row lock is held until the transaction ends.
        return Optional.ofNullable(entityManager.find(IssueEntity.class, id.toString(), LockModeType.PESSIMISTIC_WRITE))
                .map(PersistenceMapper::toDomain);
    }

    @Override
    public void update(Issue issue) {
        IssueEntity entity = entityManager.find(IssueEntity.class, issue.id().toString());
        if (entity == null) {
            throw new IllegalStateException("issue to update does not exist");
        }
        // Only what a command may change; identity, building and creation time never do.
        entity.state = issue.state().value();
        entity.version = issue.version();
        entity.category = issue.category().map(IssueCategory::value).orElse(null);
        entity.urgency = issue.urgency().map(IssueUrgency::value).orElse(null);
        entity.publishedSummaryId = issue.publishedSummaryId().map(SummaryId::toString).orElse(null);
        entity.updatedAt = Timestamps.toColumn(issue.updatedAt());
    }

    @Override
    public void appendHistory(IssueHistoryEntry entry) {
        entityManager.persist(PersistenceMapper.toEntity(entry));
    }

    @Override
    public int nextHistorySequence(IssueId id) {
        Integer last = entityManager
                .createQuery("SELECT MAX(h.sequence) FROM IssueHistoryEntity h WHERE h.issueId = :issueId", Integer.class)
                .setParameter("issueId", id.toString())
                .getSingleResult();
        return last == null ? 1 : last + 1;
    }
}
