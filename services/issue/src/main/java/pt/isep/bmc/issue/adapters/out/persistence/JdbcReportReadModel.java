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
import pt.isep.bmc.issue.application.port.out.ReportReadModel;
import pt.isep.bmc.issue.application.query.ReportDetails;
import pt.isep.bmc.issue.application.query.ReportListCriteria;
import pt.isep.bmc.issue.application.query.ReportPage;
import pt.isep.bmc.issue.application.query.ReportSummary;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.membership.UserId;
import pt.isep.bmc.issue.domain.report.ReportId;
import pt.isep.bmc.issue.domain.report.ReportSource;
import pt.isep.bmc.issue.domain.report.ReportText;

/** Query side (CQRS as packages, ADR-009): SQL projections over the same tables. */
@Repository
class JdbcReportReadModel implements ReportReadModel {

    private static final String SELECT = """
            SELECT r.id, r.issue_id, r.building_id, r.area_id, a.label AS area_label, r.author_user_id,
                   r.source, r.submitted_at, i.state, i.version%s
              FROM reports r
              JOIN issues i ON i.id = r.issue_id
              JOIN areas a ON a.id = r.area_id
            """;

    private final JdbcClient jdbc;

    JdbcReportReadModel(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ReportDetails> findDetails(ReportId id) {
        return jdbc.sql(SELECT.formatted(", r.text") + " WHERE r.id = :id")
                .param("id", id.toString())
                .query((rs, n) -> new ReportDetails(
                        new ReportId(uuid(rs, "id")),
                        new IssueId(uuid(rs, "issue_id")),
                        new BuildingId(uuid(rs, "building_id")),
                        new AreaId(uuid(rs, "area_id")),
                        rs.getString("area_label"),
                        new UserId(rs.getString("author_user_id")),
                        ReportText.restore(rs.getString("text")),
                        ReportSource.fromValue(rs.getString("source")),
                        Timestamps.fromColumn(rs.getObject("submitted_at", LocalDateTime.class)),
                        IssueState.fromValue(rs.getString("state")),
                        rs.getLong("version")))
                .optional();
    }

    @Override
    public ReportPage list(ReportListCriteria criteria) {
        StringBuilder where = new StringBuilder(" WHERE (r.author_user_id = :author");
        List<String> administered = criteria.administeredBuildings().stream().map(BuildingId::toString).toList();
        if (!administered.isEmpty()) {
            where.append(" OR r.building_id IN (:administered)");
        }
        where.append(")");
        if (criteria.building().isPresent()) {
            where.append(" AND r.building_id = :building");
        }
        // Fetch one extra row to know whether another page follows.
        String sql = SELECT.formatted("") + where
                + " ORDER BY r.submitted_at DESC, r.id DESC LIMIT :limit OFFSET :offset";

        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("author", criteria.author().value())
                .param("limit", criteria.size() + 1)
                .param("offset", (long) criteria.page() * criteria.size());
        if (!administered.isEmpty()) {
            statement = statement.param("administered", administered);
        }
        if (criteria.building().isPresent()) {
            statement = statement.param("building", criteria.building().get().toString());
        }
        List<ReportSummary> rows = new ArrayList<>(statement.query((rs, n) -> summary(rs)).list());
        boolean hasMore = rows.size() > criteria.size();
        if (hasMore) {
            rows.removeLast();
        }
        return new ReportPage(rows, criteria.page(), criteria.size(), hasMore);
    }

    private static ReportSummary summary(ResultSet rs) throws SQLException {
        return new ReportSummary(
                new ReportId(uuid(rs, "id")),
                new IssueId(uuid(rs, "issue_id")),
                new BuildingId(uuid(rs, "building_id")),
                new AreaId(uuid(rs, "area_id")),
                rs.getString("area_label"),
                new UserId(rs.getString("author_user_id")),
                ReportSource.fromValue(rs.getString("source")),
                Timestamps.fromColumn(rs.getObject("submitted_at", LocalDateTime.class)),
                IssueState.fromValue(rs.getString("state")),
                rs.getLong("version"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return UUID.fromString(rs.getString(column));
    }
}
