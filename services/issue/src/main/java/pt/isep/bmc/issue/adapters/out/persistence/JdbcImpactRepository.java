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

    /**
     * True for a confirmation {@code c} of issue {@code i} whose author is an
     * active resident of the issue's building at {@code :now}, as in
     * {@code Membership.isActiveAt}. Only these confirmations are counted or
     * shown as the caller's own.
     */
    static final String BY_ACTIVE_RESIDENT = """
            EXISTS (SELECT 1 FROM memberships m
                     WHERE m.user_id = c.user_id AND m.building_id = i.building_id
                       AND m.role = 'resident' AND m.status = 'active'
                       AND m.valid_from <= :now AND (m.valid_until IS NULL OR m.valid_until > :now))
            """;

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
    public long count(IssueId issueId, Instant now) {
        return jdbc.sql("SELECT COUNT(*) FROM impact_confirmations c JOIN issues i ON i.id = c.issue_id"
                        + " WHERE c.issue_id = :issueId AND " + BY_ACTIVE_RESIDENT)
                .param("issueId", issueId.toString())
                .param("now", Timestamps.toColumn(now))
                .query(Long.class)
                .single();
    }
}
