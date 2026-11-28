package pt.isep.bmc.issue.adapters.out.persistence;

import jakarta.persistence.EntityManager;
import java.util.Set;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.issue.application.port.out.CriticalSignalRepository;
import pt.isep.bmc.issue.domain.issue.CriticalSignal;
import pt.isep.bmc.issue.domain.issue.IssueId;

/**
 * Critical signals in {@code issue_critical_signals}. Through JPA, like the
 * issue itself: a new issue and its first signals are written by one flush, in
 * the order they were added, so the signal's foreign key finds its issue.
 */
@Repository
class JpaCriticalSignalRepository implements CriticalSignalRepository {

    private final EntityManager entityManager;

    JpaCriticalSignalRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void add(CriticalSignal signal) {
        CriticalSignalEntity entity = new CriticalSignalEntity();
        entity.issueId = signal.issueId().toString();
        entity.code = signal.code();
        entity.rulesVersion = signal.rulesVersion();
        entity.historySequence = signal.historySequence();
        entity.raisedAt = Timestamps.toColumn(signal.raisedAt());
        entityManager.persist(entity);
    }

    @Override
    public Set<String> findCodes(IssueId issueId) {
        return Set.copyOf(entityManager
                .createQuery("SELECT s.code FROM CriticalSignalEntity s WHERE s.issueId = :issueId", String.class)
                .setParameter("issueId", issueId.toString())
                .getResultList());
    }
}
