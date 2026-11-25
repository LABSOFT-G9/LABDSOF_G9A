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
import pt.isep.bmc.issue.application.query.ImpactView;
import pt.isep.bmc.issue.application.query.IssueView;
import pt.isep.bmc.issue.application.query.SuggestionView;
import pt.isep.bmc.issue.application.query.SummaryView;
import pt.isep.bmc.issue.application.query.TriageView;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.HistoryVisibility;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.issue.SuggestionRationale;
import pt.isep.bmc.issue.domain.issue.SummaryText;
import pt.isep.bmc.issue.domain.issue.TriageReason;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * Query side for issues (CQRS as packages, ADR-009): SQL projections over
 * the same tables. The current decision of an issue is the decision whose
 * resulting version is the newest one for that issue. Its suggestion is the
 * one that was applied, and only while nobody has decided: a late suggestion
 * is in the history, never here.
 */
@Repository
class JdbcIssueReadModel implements IssueReadModel {

    /**
     * {@code %s}: the internal texts, for one issue only: the reason of the
     * current decision ({@code d}) and the rationale of the suggestion ({@code g}).
     * {@code :caller} is whose own impact confirmation and subscription to show.
     */
    private static final String SELECT = """
            SELECT i.id, i.building_id, b.name AS building_name, a.label AS area_label, i.state, i.version,
                   i.category, i.urgency, i.created_at, d.decided_at%s,
                   g.category AS suggested_category, g.urgency AS suggested_urgency,
                   g.origin AS suggestion_origin, g.needs_review, g.failure_code,
                   g.rules_version, g.model_version, g.recorded_at AS suggested_at,
                   s.text AS summary_text, s.published_at AS summary_published_at,
                   (SELECT COUNT(*) FROM impact_confirmations c WHERE c.issue_id = i.id) AS impact_count,
                   EXISTS (SELECT 1 FROM impact_confirmations c
                            WHERE c.issue_id = i.id AND c.user_id = :caller) AS impact_mine,
                   EXISTS (SELECT 1 FROM issue_subscriptions n
                            WHERE n.issue_id = i.id AND n.user_id = :caller) AS subscribed
              FROM issues i
              JOIN buildings b ON b.id = i.building_id
              JOIN areas a ON a.id = i.area_id
              LEFT JOIN issue_summaries s ON s.id = i.published_summary_id
              LEFT JOIN triage_decisions d
                     ON d.issue_id = i.id
                    AND d.resulting_version = (SELECT MAX(x.resulting_version)
                                                 FROM triage_decisions x WHERE x.issue_id = i.id)
              LEFT JOIN triage_suggestions g
                     ON g.issue_id = i.id
                    AND i.category IS NULL
                    AND g.applied
                    AND g.history_sequence = (SELECT MAX(y.history_sequence)
                                                FROM triage_suggestions y WHERE y.issue_id = i.id AND y.applied)
            """;

    private static final String INTERNAL_TEXTS = ", d.reason, g.rationale";

    private final JdbcClient jdbc;

    JdbcIssueReadModel(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IssueView> findView(IssueId id, UserId caller) {
        return jdbc.sql(SELECT.formatted(INTERNAL_TEXTS) + " WHERE i.id = :id")
                .param("id", id.toString())
                .param("caller", caller.value())
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
        // Shared with the building: a published summary makes the issue visible to its members (US-04).
        List<String> member = criteria.memberBuildings().stream().map(BuildingId::toString).toList();
        if (!member.isEmpty()) {
            where.append(" OR (i.published_summary_id IS NOT NULL AND i.building_id IN (:member))");
        }
        where.append(")");
        if (criteria.building().isPresent()) {
            where.append(" AND i.building_id = :building");
        }
        // Fetch one extra row to know whether another page follows.
        String sql = SELECT.formatted("") + where + " ORDER BY i.created_at DESC, i.id DESC LIMIT :limit OFFSET :offset";
        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("reporter", criteria.reporter().value())
                .param("caller", criteria.reporter().value())
                .param("limit", criteria.size() + 1)
                .param("offset", (long) criteria.page() * criteria.size());
        if (!administered.isEmpty()) {
            statement = statement.param("administered", administered);
        }
        if (!member.isEmpty()) {
            statement = statement.param("member", member);
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
                               COALESCE(d.category, g.category) AS category,
                               COALESCE(d.urgency, g.urgency) AS urgency,
                               d.reason, g.origin, g.applied
                          FROM issue_history h
                          LEFT JOIN triage_decisions d
                                 ON h.type = 'triage_decided'
                                AND d.issue_id = h.issue_id
                                AND d.resulting_version = h.issue_version
                          LEFT JOIN triage_suggestions g
                                 ON h.type = 'triage_suggested'
                                AND g.issue_id = h.issue_id
                                AND g.history_sequence = h.sequence
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
                        Optional.ofNullable(rs.getString("reason")).map(TriageReason::restore),
                        Optional.ofNullable(rs.getString("origin")).map(PersistenceMapper::origin),
                        // Read as an object: NULL on every entry that is not a suggestion.
                        Optional.ofNullable(rs.getObject("applied", Boolean.class))))
                .list();
    }

    /** @param withInternalTexts whether the query selected {@link #INTERNAL_TEXTS} */
    private static IssueView view(ResultSet rs, boolean withInternalTexts) throws SQLException {
        String category = rs.getString("category");
        Optional<TriageView> triage = category == null
                ? Optional.empty()
                : Optional.of(new TriageView(
                        PersistenceMapper.category(category),
                        PersistenceMapper.urgency(rs.getString("urgency")),
                        withInternalTexts
                                ? Optional.ofNullable(rs.getString("reason")).map(TriageReason::restore)
                                : Optional.empty(),
                        Timestamps.fromColumn(rs.getObject("decided_at", LocalDateTime.class))));
        String suggestedCategory = rs.getString("suggested_category");
        Optional<SuggestionView> suggestion = suggestedCategory == null
                ? Optional.empty()
                : Optional.of(new SuggestionView(
                        PersistenceMapper.category(suggestedCategory),
                        PersistenceMapper.urgency(rs.getString("suggested_urgency")),
                        PersistenceMapper.origin(rs.getString("suggestion_origin")),
                        withInternalTexts
                                ? Optional.of(SuggestionRationale.restore(rs.getString("rationale")))
                                : Optional.empty(),
                        rs.getBoolean("needs_review"),
                        Optional.ofNullable(rs.getString("failure_code")),
                        Timestamps.fromColumn(rs.getObject("suggested_at", LocalDateTime.class)),
                        Optional.ofNullable(rs.getString("rules_version")),
                        Optional.ofNullable(rs.getString("model_version"))));
        String summaryText = rs.getString("summary_text");
        Optional<SummaryView> summary = summaryText == null
                ? Optional.empty()
                : Optional.of(new SummaryView(
                        SummaryText.restore(summaryText),
                        Timestamps.fromColumn(rs.getObject("summary_published_at", LocalDateTime.class))));
        // No summary, no impact: nobody else can see the issue yet.
        Optional<ImpactView> impact = summary.isEmpty()
                ? Optional.empty()
                : Optional.of(new ImpactView(rs.getLong("impact_count"), rs.getBoolean("impact_mine")));
        return new IssueView(
                new IssueId(UUID.fromString(rs.getString("id"))),
                new BuildingId(UUID.fromString(rs.getString("building_id"))),
                rs.getString("building_name"),
                rs.getString("area_label"),
                IssueState.fromValue(rs.getString("state")),
                rs.getLong("version"),
                Timestamps.fromColumn(rs.getObject("created_at", LocalDateTime.class)),
                triage,
                suggestion,
                summary,
                impact,
                rs.getBoolean("subscribed"));
    }
}
