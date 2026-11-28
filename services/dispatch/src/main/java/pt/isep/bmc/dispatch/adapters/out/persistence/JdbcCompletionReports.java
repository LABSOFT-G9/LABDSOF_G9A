package pt.isep.bmc.dispatch.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.dispatch.application.port.out.CompletionReportRepository;
import pt.isep.bmc.dispatch.domain.intervention.CompletionReport;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/** Completion reports through plain SQL; written under the intervention's lock. */
@Repository
class JdbcCompletionReports implements CompletionReportRepository {

    private final JdbcClient jdbc;

    JdbcCompletionReports(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(CompletionReport report) {
        jdbc.sql("""
                        INSERT INTO completion_reports
                          (intervention_id, number, summary, performed_at, reporter_role, reason, reported_by,
                           reported_at, intervention_version)
                        VALUES (:intervention, :number, :summary, :performedAt, :role, :reason, :by, :at, :version)
                        """)
                .param("intervention", report.interventionId().toString())
                .param("number", report.number())
                .param("summary", report.summary())
                .param("performedAt", Timestamps.toColumn(report.performedAt()))
                .param("role", report.reporterRole())
                .param("reason", report.reason().orElse(null))
                .param("by", report.reportedBy().value())
                .param("at", Timestamps.toColumn(report.reportedAt()))
                .param("version", report.interventionVersion())
                .update();
    }

    @Override
    public Optional<CompletionReport> findLatest(InterventionId interventionId) {
        return jdbc.sql("SELECT * FROM completion_reports WHERE intervention_id = :id ORDER BY number DESC LIMIT 1")
                .param("id", interventionId.toString())
                .query(JdbcCompletionReports::row)
                .optional();
    }

    @Override
    public void recordRework(CompletionReport report) {
        CompletionReport.Rework rework = report.rework().orElseThrow();
        int updated = jdbc.sql("""
                        UPDATE completion_reports SET rework_reason = :reason, rework_by = :by, rework_at = :at
                         WHERE intervention_id = :intervention AND number = :number AND rework_at IS NULL
                        """)
                .param("reason", rework.reason())
                .param("by", rework.requestedBy().value())
                .param("at", Timestamps.toColumn(rework.requestedAt()))
                .param("intervention", report.interventionId().toString())
                .param("number", report.number())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("a report is sent back once");
        }
    }

    private static CompletionReport row(ResultSet rs, int n) throws SQLException {
        Optional<CompletionReport.Rework> rework = Optional.ofNullable(rs.getObject("rework_at", LocalDateTime.class))
                .map(Timestamps::fromColumn)
                .map(at -> {
                    try {
                        return new CompletionReport.Rework(rs.getString("rework_reason"),
                                new UserId(rs.getString("rework_by")), at);
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
        return new CompletionReport(
                new InterventionId(UUID.fromString(rs.getString("intervention_id"))),
                rs.getInt("number"),
                rs.getString("summary"),
                Timestamps.fromColumn(rs.getObject("performed_at", LocalDateTime.class)),
                rs.getString("reporter_role"),
                Optional.ofNullable(rs.getString("reason")),
                new UserId(rs.getString("reported_by")),
                Timestamps.fromColumn(rs.getObject("reported_at", LocalDateTime.class)),
                rs.getLong("intervention_version"),
                rework);
    }
}
