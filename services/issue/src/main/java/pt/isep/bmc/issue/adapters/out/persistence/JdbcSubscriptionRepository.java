package pt.isep.bmc.issue.adapters.out.persistence;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.issue.application.port.out.SubscriptionRepository;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.membership.UserId;

/** Subscriptions in {@code issue_subscriptions}: no row means not subscribed. */
@Repository
class JdbcSubscriptionRepository implements SubscriptionRepository {

    private final JdbcClient jdbc;

    JdbcSubscriptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(IssueId issueId, UserId userId) {
        return jdbc.sql("SELECT COUNT(*) FROM issue_subscriptions WHERE issue_id = :issueId AND user_id = :userId")
                .param("issueId", issueId.toString())
                .param("userId", userId.value())
                .query(Long.class)
                .single() > 0;
    }

    @Override
    public void add(IssueId issueId, UserId userId, Instant subscribedAt) {
        jdbc.sql("INSERT INTO issue_subscriptions (issue_id, user_id, subscribed_at) VALUES (:issueId, :userId, :at)")
                .param("issueId", issueId.toString())
                .param("userId", userId.value())
                .param("at", Timestamps.toColumn(subscribedAt))
                .update();
    }

    @Override
    public void remove(IssueId issueId, UserId userId) {
        jdbc.sql("DELETE FROM issue_subscriptions WHERE issue_id = :issueId AND user_id = :userId")
                .param("issueId", issueId.toString())
                .param("userId", userId.value())
                .update();
    }
}
