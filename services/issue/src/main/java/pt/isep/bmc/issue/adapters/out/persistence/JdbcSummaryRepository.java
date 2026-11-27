package pt.isep.bmc.issue.adapters.out.persistence;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.issue.application.port.out.SummaryRepository;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueSummary;
import pt.isep.bmc.issue.domain.issue.SummaryId;
import pt.isep.bmc.issue.domain.issue.SummaryText;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * Published summaries in {@code issue_summaries}. Plain SQL in the caller's
 * transaction: the insert runs at once, before the issue row that points at
 * the summary is written.
 */
@Repository
class JdbcSummaryRepository implements SummaryRepository {

    private final JdbcClient jdbc;

    JdbcSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(IssueSummary summary) {
        jdbc.sql("""
                        INSERT INTO issue_summaries (id, issue_id, resulting_version, text, published_by, published_at)
                        VALUES (:id, :issueId, :resultingVersion, :text, :publishedBy, :publishedAt)
                        """)
                .param("id", summary.id().toString())
                .param("issueId", summary.issueId().toString())
                .param("resultingVersion", summary.resultingVersion())
                .param("text", summary.text().value())
                .param("publishedBy", summary.publishedBy().value())
                .param("publishedAt", Timestamps.toColumn(summary.publishedAt()))
                .update();
    }

    @Override
    public Optional<IssueSummary> find(IssueId issueId, long resultingVersion) {
        return jdbc.sql("""
                        SELECT id, issue_id, resulting_version, text, published_by, published_at
                          FROM issue_summaries
                         WHERE issue_id = :issueId AND resulting_version = :resultingVersion
                        """)
                .param("issueId", issueId.toString())
                .param("resultingVersion", resultingVersion)
                .query((rs, n) -> new IssueSummary(
                        new SummaryId(UUID.fromString(rs.getString("id"))),
                        new IssueId(UUID.fromString(rs.getString("issue_id"))),
                        SummaryText.restore(rs.getString("text")),
                        new UserId(rs.getString("published_by")),
                        rs.getLong("resulting_version"),
                        Timestamps.fromColumn(rs.getObject("published_at", LocalDateTime.class))))
                .optional();
    }
}
