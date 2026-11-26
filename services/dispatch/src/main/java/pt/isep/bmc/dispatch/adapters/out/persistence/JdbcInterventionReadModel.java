package pt.isep.bmc.dispatch.adapters.out.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.dispatch.application.port.out.InterventionReadModel;
import pt.isep.bmc.dispatch.application.query.InterventionView;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.intervention.DeclineReason;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.intervention.Money;
import pt.isep.bmc.dispatch.domain.intervention.ResponseDecision;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * Query side for interventions (CQRS as packages, ADR-009): the intervention
 * with its contractor, then its proposal versions with their approval and
 * response, oldest first.
 */
@Repository
class JdbcInterventionReadModel implements InterventionReadModel {

    private static final String SELECT = """
            SELECT i.id, i.issue_id, i.building_id, i.contractor_id, i.state, i.version, i.current_proposal_version,
                   i.approved_proposal_version, i.agreed_proposal_version, i.contractor_contacted,
                   i.created_at, i.updated_at, c.display_name, c.synthetic, c.user_id
              FROM interventions i
              JOIN contractors c ON c.id = i.contractor_id
            """;

    private static final String PROPOSALS = """
            SELECT p.number, p.author_role, p.scope_extract, p.window_start, p.window_end, p.terms,
                   p.cost_amount, p.cost_currency, p.created_at, p.approved_at,
                   r.decision, r.reason_code, r.note, r.counter_proposal_version, r.responded_at
              FROM proposal_versions p
              LEFT JOIN contractor_responses r ON r.intervention_id = p.intervention_id AND r.proposal_version = p.number
             WHERE p.intervention_id = :id
             ORDER BY p.number
            """;

    private final JdbcClient jdbc;

    JdbcInterventionReadModel(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<InterventionView> find(InterventionId id) {
        return jdbc.sql(SELECT + " WHERE i.id = :id").param("id", id.toString())
                .query(JdbcInterventionReadModel::row).optional()
                .map(this::withProposals);
    }

    @Override
    public List<InterventionView> findByIssue(IssueId issueId) {
        return jdbc.sql(SELECT + " WHERE i.issue_id = :issue ORDER BY i.created_at DESC, i.id DESC")
                .param("issue", issueId.toString())
                .query(JdbcInterventionReadModel::row)
                .list()
                .stream()
                .map(this::withProposals)
                .toList();
    }

    @Override
    public List<InterventionView> findSentTo(UserId contractorUserId) {
        return jdbc.sql(SELECT + " WHERE c.user_id = :user AND i.contractor_contacted"
                        + " ORDER BY i.updated_at DESC, i.id DESC")
                .param("user", contractorUserId.value())
                .query(JdbcInterventionReadModel::row)
                .list()
                .stream()
                .map(this::withProposals)
                .toList();
    }

    /** An intervention row, still without its proposals. */
    private record Row(
            InterventionId id,
            IssueId issueId,
            BuildingId buildingId,
            InterventionView.Contractor contractor,
            InterventionState state,
            long version,
            int currentProposalVersion,
            Optional<Integer> approvedProposalVersion,
            Optional<Integer> agreedProposalVersion,
            boolean contractorContacted,
            Instant createdAt,
            Instant updatedAt) {
    }

    private InterventionView withProposals(Row row) {
        List<InterventionView.Proposal> proposals = jdbc.sql(PROPOSALS)
                .param("id", row.id().toString())
                .query(JdbcInterventionReadModel::proposal)
                .list();
        InterventionView.Proposal current = proposals.stream()
                .filter(p -> p.proposalVersion() == row.currentProposalVersion())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the current proposal version is missing"));
        return new InterventionView(row.id(), row.issueId(), row.buildingId(), row.contractor(), row.state(),
                row.version(), current, proposals, row.approvedProposalVersion(), row.agreedProposalVersion(),
                row.contractorContacted(), row.createdAt(), row.updatedAt());
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                new InterventionId(UUID.fromString(rs.getString("id"))),
                new IssueId(UUID.fromString(rs.getString("issue_id"))),
                new BuildingId(UUID.fromString(rs.getString("building_id"))),
                new InterventionView.Contractor(
                        new ContractorId(UUID.fromString(rs.getString("contractor_id"))),
                        rs.getString("display_name"),
                        rs.getBoolean("synthetic"),
                        Optional.ofNullable(rs.getString("user_id")).map(UserId::new)),
                InterventionState.fromValue(rs.getString("state")),
                rs.getLong("version"),
                rs.getInt("current_proposal_version"),
                optionalInt(rs, "approved_proposal_version"),
                optionalInt(rs, "agreed_proposal_version"),
                rs.getBoolean("contractor_contacted"),
                time(rs, "created_at"),
                time(rs, "updated_at"));
    }

    private static InterventionView.Proposal proposal(ResultSet rs, int n) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("cost_amount");
        Optional<Money> cost = amount == null ? Optional.empty() : Optional.of(new Money(amount, rs.getString("cost_currency")));
        String decision = rs.getString("decision");
        Optional<InterventionView.Response> response = decision == null
                ? Optional.empty()
                : Optional.of(new InterventionView.Response(
                        ResponseDecision.fromValue(decision).orElseThrow(),
                        Optional.ofNullable(rs.getString("reason_code")).flatMap(DeclineReason::fromValue),
                        Optional.ofNullable(rs.getString("note")),
                        optionalInt(rs, "counter_proposal_version"),
                        time(rs, "responded_at")));
        return new InterventionView.Proposal(
                rs.getInt("number"),
                rs.getString("author_role"),
                rs.getString("scope_extract"),
                new TimeWindow(time(rs, "window_start"), time(rs, "window_end")),
                rs.getString("terms"),
                cost,
                time(rs, "created_at"),
                Optional.ofNullable(rs.getObject("approved_at", LocalDateTime.class)).map(Timestamps::fromColumn),
                response);
    }

    private static Optional<Integer> optionalInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? Optional.empty() : Optional.of(value);
    }

    private static Instant time(ResultSet rs, String column) throws SQLException {
        return Timestamps.fromColumn(rs.getObject(column, LocalDateTime.class));
    }
}
