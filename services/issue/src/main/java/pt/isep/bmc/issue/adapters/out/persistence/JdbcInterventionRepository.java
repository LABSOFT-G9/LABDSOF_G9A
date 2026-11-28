package pt.isep.bmc.issue.adapters.out.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.issue.application.port.out.InterventionRepository;
import pt.isep.bmc.issue.domain.dispatch.InterventionProjection;
import pt.isep.bmc.issue.domain.dispatch.InterventionState;
import pt.isep.bmc.issue.domain.issue.IssueId;

/** Interventions in {@code issue_interventions}. Plain SQL in the caller's transaction, under the issue's lock. */
@Repository
class JdbcInterventionRepository implements InterventionRepository {

    private final JdbcClient jdbc;

    JdbcInterventionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<InterventionProjection> forIssue(IssueId issueId) {
        return jdbc.sql("""
                        SELECT intervention_id, issue_id, state, last_version, contractor_user_id,
                               first_applied_at, updated_at
                          FROM issue_interventions
                         WHERE issue_id = :issueId
                         ORDER BY first_applied_at, intervention_id
                        """)
                .param("issueId", issueId.toString())
                .query((rs, row) -> new InterventionProjection(
                        UUID.fromString(rs.getString("intervention_id")),
                        new IssueId(UUID.fromString(rs.getString("issue_id"))),
                        InterventionState.fromValue(rs.getString("state")).orElseThrow(),
                        rs.getLong("last_version"),
                        Optional.ofNullable(rs.getString("contractor_user_id")),
                        Timestamps.fromColumn(rs.getObject("first_applied_at", LocalDateTime.class)),
                        Timestamps.fromColumn(rs.getObject("updated_at", LocalDateTime.class))))
                .list();
    }

    @Override
    public void save(InterventionProjection intervention) {
        jdbc.sql("""
                        INSERT INTO issue_interventions (intervention_id, issue_id, state, last_version,
                                                         contractor_user_id, first_applied_at, updated_at)
                        VALUES (:id, :issueId, :state, :version, :contractor, :firstAppliedAt, :updatedAt)
                        ON DUPLICATE KEY UPDATE state = VALUES(state), last_version = VALUES(last_version),
                                                contractor_user_id = VALUES(contractor_user_id),
                                                updated_at = VALUES(updated_at)
                        """)
                .param("id", intervention.interventionId().toString())
                .param("issueId", intervention.issueId().toString())
                .param("state", intervention.state().value())
                .param("version", intervention.lastVersion())
                .param("contractor", intervention.contractorUserId().orElse(null))
                .param("firstAppliedAt", Timestamps.toColumn(intervention.firstAppliedAt()))
                .param("updatedAt", Timestamps.toColumn(intervention.updatedAt()))
                .update();
    }
}
