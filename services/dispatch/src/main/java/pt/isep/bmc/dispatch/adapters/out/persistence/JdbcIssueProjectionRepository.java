package pt.isep.bmc.dispatch.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.dispatch.application.port.out.DuplicateProjectionRowException;
import pt.isep.bmc.dispatch.application.port.out.IssueProjectionRepository;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/** Plain SQL, so the row lock ({@code FOR UPDATE}) is explicit. Runs on the transaction's connection. */
@Repository
class JdbcIssueProjectionRepository implements IssueProjectionRepository {

    private static final String COLUMNS = """
            SELECT issue_id, building_id, state, version, dispatchable, category, urgency, area_label,
                   published_summary_id, applied_at
              FROM issue_projection
            """;
    private static final String SELECT = COLUMNS + " WHERE issue_id = :id";

    private final JdbcClient jdbc;

    JdbcIssueProjectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProjectedIssue> find(IssueId id) {
        return jdbc.sql(SELECT).param("id", id.toString()).query(JdbcIssueProjectionRepository::row).optional();
    }

    @Override
    public Map<IssueId, ProjectedIssue> findAll(Collection<IssueId> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql(COLUMNS + " WHERE issue_id IN (:ids)")
                .param("ids", ids.stream().map(IssueId::toString).distinct().toList())
                .query(JdbcIssueProjectionRepository::row)
                .list()
                .stream()
                .collect(Collectors.toMap(ProjectedIssue::issueId, Function.identity()));
    }

    @Override
    public Optional<ProjectedIssue> findForUpdate(IssueId id) {
        return jdbc.sql(SELECT + " FOR UPDATE").param("id", id.toString())
                .query(JdbcIssueProjectionRepository::row).optional();
    }

    @Override
    public void insert(ProjectedIssue issue) {
        try {
            jdbc.sql("""
                            INSERT INTO issue_projection
                              (issue_id, building_id, state, version, dispatchable, category, urgency, area_label,
                               published_summary_id, applied_at)
                            VALUES (:id, :building, :state, :version, :dispatchable, :category, :urgency, :area,
                                    :summary, :applied)
                            """)
                    .params(params(issue))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateProjectionRowException();
        }
    }

    @Override
    public void update(ProjectedIssue issue) {
        jdbc.sql("""
                        UPDATE issue_projection
                           SET building_id = :building, state = :state, version = :version,
                               dispatchable = :dispatchable, category = :category, urgency = :urgency,
                               area_label = :area, published_summary_id = :summary, applied_at = :applied
                         WHERE issue_id = :id
                        """)
                .params(params(issue))
                .update();
    }

    private static Map<String, Object> params(ProjectedIssue issue) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", issue.issueId().toString());
        params.put("building", issue.buildingId().toString());
        params.put("state", issue.state());
        params.put("version", issue.version());
        params.put("dispatchable", issue.dispatchable());
        params.put("category", issue.category().orElse(null));
        params.put("urgency", issue.urgency().orElse(null));
        params.put("area", issue.areaLabel());
        params.put("summary", issue.publishedSummaryId().orElse(null));
        params.put("applied", Timestamps.toColumn(issue.appliedAt()));
        return params;
    }

    private static ProjectedIssue row(ResultSet rs, int n) throws SQLException {
        return new ProjectedIssue(
                new IssueId(UUID.fromString(rs.getString("issue_id"))),
                new BuildingId(UUID.fromString(rs.getString("building_id"))),
                rs.getString("state"),
                rs.getLong("version"),
                rs.getBoolean("dispatchable"),
                Optional.ofNullable(rs.getString("category")),
                Optional.ofNullable(rs.getString("urgency")),
                rs.getString("area_label"),
                Optional.ofNullable(rs.getString("published_summary_id")),
                Timestamps.fromColumn(rs.getObject("applied_at", LocalDateTime.class)));
    }
}
