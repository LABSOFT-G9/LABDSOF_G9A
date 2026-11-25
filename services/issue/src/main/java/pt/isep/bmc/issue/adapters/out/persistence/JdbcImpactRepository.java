package pt.isep.bmc.issue.adapters.out.persistence;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.issue.application.port.out.ImpactRepository;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.membership.UserId;

/** Impact confirmations in {@code impact_confirmations}: one row per user and issue. */
@Repository
class JdbcImpactRepository implements ImpactRepository {

    private final JdbcClient jdbc;

    JdbcImpactRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(IssueId issueId, UserId userId) {
        return jdbc.sql("SELECT COUNT(*) FROM impact_confirmations WHERE issue_id = :issueId AND user_id = :userId")
                .param("issueId", issueId.toString())
                .param("userId", userId.value())
                .query(Long.class)
                .single() > 0;
    }

    @Override
    public void add(IssueId issueId, UserId userId, Instant confirmedAt) {
        jdbc.sql("INSERT INTO impact_confirmations (issue_id, user_id, confirmed_at) VALUES (:issueId, :userId, :at)")
                .param("issueId", issueId.toString())
                .param("userId", userId.value())
                .param("at", Timestamps.toColumn(confirmedAt))
                .update();
    }

    @Override
    public void remove(IssueId issueId, UserId userId) {
        jdbc.sql("DELETE FROM impact_confirmations WHERE issue_id = :issueId AND user_id = :userId")
                .param("issueId", issueId.toString())
                .param("userId", userId.value())
                .update();
    }

    @Override
    public long count(IssueId issueId) {
        return jdbc.sql("SELECT COUNT(*) FROM impact_confirmations WHERE issue_id = :issueId")
                .param("issueId", issueId.toString())
                .query(Long.class)
                .single();
    }
}
