package pt.isep.bmc.dispatch.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.dispatch.application.port.out.InterventionReadModel;
import pt.isep.bmc.dispatch.application.query.InterventionView;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/** Query side for interventions (CQRS as packages, ADR-009): one SQL projection with the current proposal. */
@Repository
class JdbcInterventionReadModel implements InterventionReadModel {

    private static final String SELECT = """
            SELECT i.id, i.issue_id, i.building_id, i.contractor_id, i.state, i.version, i.created_at, i.updated_at,
                   c.display_name, c.synthetic,
                   p.number, p.author_role, p.scope_extract, p.window_start, p.window_end, p.terms,
                   p.created_at AS proposal_created_at
              FROM interventions i
              JOIN contractors c ON c.id = i.contractor_id
              JOIN proposal_versions p ON p.intervention_id = i.id AND p.number = i.current_proposal_version
            """;

    private final JdbcClient jdbc;

    JdbcInterventionReadModel(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<InterventionView> find(InterventionId id) {
        return jdbc.sql(SELECT + " WHERE i.id = :id").param("id", id.toString())
                .query(JdbcInterventionReadModel::row).optional();
    }

    @Override
    public List<InterventionView> findByIssue(IssueId issueId) {
        return jdbc.sql(SELECT + " WHERE i.issue_id = :issue ORDER BY i.created_at DESC, i.id DESC")
                .param("issue", issueId.toString())
                .query(JdbcInterventionReadModel::row)
                .list();
    }

    private static InterventionView row(ResultSet rs, int n) throws SQLException {
        return new InterventionView(
                new InterventionId(UUID.fromString(rs.getString("id"))),
                new IssueId(UUID.fromString(rs.getString("issue_id"))),
                new BuildingId(UUID.fromString(rs.getString("building_id"))),
                new InterventionView.Contractor(
                        new ContractorId(UUID.fromString(rs.getString("contractor_id"))),
                        rs.getString("display_name"),
                        rs.getBoolean("synthetic")),
                InterventionState.fromValue(rs.getString("state")),
                rs.getLong("version"),
                new InterventionView.Proposal(
                        rs.getInt("number"),
                        rs.getString("author_role"),
                        rs.getString("scope_extract"),
                        new TimeWindow(
                                Timestamps.fromColumn(rs.getObject("window_start", LocalDateTime.class)),
                                Timestamps.fromColumn(rs.getObject("window_end", LocalDateTime.class))),
                        rs.getString("terms"),
                        Timestamps.fromColumn(rs.getObject("proposal_created_at", LocalDateTime.class))),
                Timestamps.fromColumn(rs.getObject("created_at", LocalDateTime.class)),
                Timestamps.fromColumn(rs.getObject("updated_at", LocalDateTime.class)));
    }
}
