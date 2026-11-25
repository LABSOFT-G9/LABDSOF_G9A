package pt.isep.bmc.issue.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.issue.application.port.out.IssueReadModel;
import pt.isep.bmc.issue.application.query.IssueHistoryItem;
import pt.isep.bmc.issue.application.query.IssueListCriteria;
import pt.isep.bmc.issue.application.query.IssuePage;
import pt.isep.bmc.issue.application.query.IssueView;
import pt.isep.bmc.issue.application.query.TriageView;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.HistoryVisibility;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.issue.TriageReason;

/**
 * Query side for issues (CQRS as packages, ADR-009): SQL projections over
 * the same tables. The current decision of an issue is the decision whose
 * resulting version is the newest one for that issue.
 */
@Repository
class JdbcIssueReadModel implements IssueReadModel {

    /** {@code %s}: extra columns of the current decision ({@code d}). */
    private static final String SELECT = """
            SELECT i.id, i.building_id, b.name AS building_name, a.label AS area_label, i.state, i.version,
                   i.category, i.urgency, i.created_at, d.decided_at%s
              FROM issues i
              JOIN buildings b ON b.id = i.building_id
              JOIN areas a ON a.id = i.area_id
              LEFT JOIN triage_decisions d
                     ON d.issue_id = i.id
                    AND d.resulting_version = (SELECT MAX(x.resulting_version)
                                                 FROM triage_decisions x WHERE x.issue_id = i.id)
            """;

    private final JdbcClient jdbc;

    JdbcIssueReadModel(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IssueView> findView(IssueId id) {
        return jdbc.sql(SELECT.formatted(", d.reason") + " WHERE i.id = :id")
                .param("id", id.toString())
                .query((rs, n) -> view(rs, true))
                .optional();
    }

    @Override
    public IssuePage list(IssueListCriteria criteria) {
        StringBuilder where = new StringBuilder(
                " WHERE (EXISTS (SELECT 1 FROM reports r WHERE r.issue_id = i.id AND r.author_user_id = :reporter)");
        List<String> administered = criteria.administeredBuildings().stream().map(BuildingId::toString).toList();
        if (!administered.isEmpty()) {
            where.append(" OR i.building_id IN (:administered)");
        }
        where.append(")");
        if (criteria.building().isPresent()) {
            where.append(" AND i.building_id = :building");
        }
        // Fetch one extra row to know whether another page follows.
        String sql = SELECT.formatted("") + where + " ORDER BY i.created_at DESC, i.id DESC LIMIT :limit OFFSET :offset";
        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("reporter", criteria.reporter().value())
                .param("limit", criteria.size() + 1)
                .param("offset", (long) criteria.page() * criteria.size());
        if (!administered.isEmpty()) {
            statement = statement.param("administered", administered);
        }
        if (criteria.building().isPresent()) {
            statement = statement.param("building", criteria.building().get().toString());
        }
        List<IssueView> rows = new ArrayList<>(statement.query((rs, n) -> view(rs, false)).list());
        boolean hasMore = rows.size() > criteria.size();
        if (hasMore) {
            rows.removeLast();
        }
        return new IssuePage(rows, criteria.page(), criteria.size(), hasMore);
    }

    @Override
    public List<IssueHistoryItem> findHistory(IssueId id) {
        return jdbc.sql("""
                        SELECT h.sequence, h.type, h.visibility, h.actor_role, h.occurred_at,
                               d.category, d.urgency, d.reason
                          FROM issue_history h
                          LEFT JOIN triage_decisions d
                                 ON h.type = 'triage_decided'
                                AND d.issue_id = h.issue_id
                                AND d.resulting_version = h.issue_version
                         WHERE h.issue_id = :id
                         ORDER BY h.sequence
                        """)
                .param("id", id.toString())
                .query((rs, n) -> new IssueHistoryItem(
                        rs.getInt("sequence"),
                        rs.getString("type"),
                        "public".equals(rs.getString("visibility")) ? HistoryVisibility.PUBLIC : HistoryVisibility.INTERNAL,
                        rs.getString("actor_role"),
                        Timestamps.fromColumn(rs.getObject("occurred_at", LocalDateTime.class)),
                        Optional.ofNullable(rs.getString("category")).map(PersistenceMapper::category),
                        Optional.ofNullable(rs.getString("urgency")).map(PersistenceMapper::urgency),
                        Optional.ofNullable(rs.getString("reason")).map(TriageReason::restore)))
                .list();
    }

    private static IssueView view(ResultSet rs, boolean withReason) throws SQLException {
        String category = rs.getString("category");
        Optional<TriageView> triage = category == null
                ? Optional.empty()
                : Optional.of(new TriageView(
                        PersistenceMapper.category(category),
                        PersistenceMapper.urgency(rs.getString("urgency")),
                        withReason ? Optional.ofNullable(rs.getString("reason")).map(TriageReason::restore) : Optional.empty(),
                        Timestamps.fromColumn(rs.getObject("decided_at", LocalDateTime.class))));
        return new IssueView(
                new IssueId(UUID.fromString(rs.getString("id"))),
                new BuildingId(UUID.fromString(rs.getString("building_id"))),
                rs.getString("building_name"),
                rs.getString("area_label"),
                IssueState.fromValue(rs.getString("state")),
                rs.getLong("version"),
                Timestamps.fromColumn(rs.getObject("created_at", LocalDateTime.class)),
                triage);
    }
}
