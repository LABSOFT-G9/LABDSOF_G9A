package pt.isep.bmc.issue.adapters.out.persistence;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.issue.application.port.out.IssueRepository;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;

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
    public void appendHistory(IssueHistoryEntry entry) {
        entityManager.persist(PersistenceMapper.toEntity(entry));
    }
}
