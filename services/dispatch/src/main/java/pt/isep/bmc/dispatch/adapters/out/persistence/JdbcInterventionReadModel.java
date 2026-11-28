package pt.isep.bmc.dispatch.adapters.out.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.dispatch.application.port.out.InterventionReadModel;
import pt.isep.bmc.dispatch.application.query.BookingView;
import pt.isep.bmc.dispatch.application.query.CompletionView;
import pt.isep.bmc.dispatch.application.query.InterventionView;
import pt.isep.bmc.dispatch.domain.booking.BookingAction;
import pt.isep.bmc.dispatch.domain.booking.BookingId;
import pt.isep.bmc.dispatch.domain.booking.BookingSource;
import pt.isep.bmc.dispatch.domain.booking.BookingState;
import pt.isep.bmc.dispatch.domain.booking.ManualSource;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.intervention.CancelReason;
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
 * response, oldest first, then its bookings and completion reports. A list
 * reads each kind of child row for all its interventions in one query, so
 * the number of queries does not grow with its length.
 */
@Repository
class JdbcInterventionReadModel implements InterventionReadModel {

    private static final String SELECT = """
            SELECT i.id, i.issue_id, i.building_id, i.contractor_id, i.state, i.version, i.current_proposal_version,
                   i.approved_proposal_version, i.agreed_proposal_version, i.contractor_contacted,
                   i.cancel_reason_code, i.cancel_note, i.created_at, i.updated_at,
                   c.display_name, c.synthetic, c.user_id
              FROM interventions i
              JOIN contractors c ON c.id = i.contractor_id
            """;

    private static final String PROPOSALS = """
            SELECT p.intervention_id, p.number, p.author_role, p.scope_extract, p.window_start, p.window_end, p.terms,
                   p.cost_amount, p.cost_currency, p.created_at, p.approved_at,
                   r.decision, r.reason_code, r.note, r.counter_proposal_version, r.responded_at
              FROM proposal_versions p
              LEFT JOIN contractor_responses r ON r.intervention_id = p.intervention_id AND r.proposal_version = p.number
             WHERE p.intervention_id IN (:ids)
             ORDER BY p.intervention_id, p.number
            """;

    private static final String BOOKINGS = """
            SELECT intervention_id, id, state, source, window_start, window_end, external_ref, reason_code, pending_action,
                   last_reconciled_at, manual_window_start, manual_window_end, manual_source, manual_reason, manual_at,
                   created_at, updated_at
              FROM bookings
             WHERE intervention_id IN (:ids)
             ORDER BY created_at, id
            """;

    private static final String CALLS = """
            SELECT booking_id, action, outcome, occurred_at FROM booking_calls
             WHERE booking_id IN (:ids) ORDER BY sequence
            """;

    private static final String COMPLETIONS = """
            SELECT intervention_id, number, summary, performed_at, reporter_role, reason, reported_at, rework_reason,
                   rework_at
              FROM completion_reports WHERE intervention_id IN (:ids) ORDER BY intervention_id, number
            """;

    private final JdbcClient jdbc;

    JdbcInterventionReadModel(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<InterventionView> find(InterventionId id) {
        return jdbc.sql(SELECT + " WHERE i.id = :id").param("id", id.toString())
                .query(JdbcInterventionReadModel::row).optional()
                .map(row -> withProposals(List.of(row)).getFirst());
    }

    @Override
    public List<InterventionView> findByIssue(IssueId issueId) {
        return withProposals(jdbc.sql(SELECT + " WHERE i.issue_id = :issue ORDER BY i.created_at DESC, i.id DESC")
                .param("issue", issueId.toString())
                .query(JdbcInterventionReadModel::row)
                .list());
    }

    @Override
    public List<InterventionView> findSentTo(UserId contractorUserId) {
        return withProposals(jdbc.sql(SELECT + " WHERE c.user_id = :user AND i.contractor_contacted"
                        + " ORDER BY i.updated_at DESC, i.id DESC")
                .param("user", contractorUserId.value())
                .query(JdbcInterventionReadModel::row)
                .list());
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
            Optional<CancelReason> cancelReason,
            Optional<String> cancelNote,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * The rows, in their order, each with its proposals, bookings (with their
     * calls) and completion reports: one query for each kind, for all rows.
     */
    private List<InterventionView> withProposals(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> ids = rows.stream().map(r -> r.id().toString()).toList();
        Map<InterventionId, List<InterventionView.Proposal>> proposals = grouped(
                jdbc.sql(PROPOSALS).param("ids", ids).query(JdbcInterventionReadModel::proposalOf).list());
        List<Owned<BookingView>> bookingRows = jdbc.sql(BOOKINGS).param("ids", ids)
                .query(JdbcInterventionReadModel::bookingOf).list();
        Map<BookingId, List<BookingView.Call>> calls = calls(bookingRows.stream().map(b -> b.item().bookingId()).toList());
        Map<InterventionId, List<BookingView>> bookings = grouped(bookingRows.stream()
                .map(b -> new Owned<>(b.interventionId(), withCalls(b.item(), calls.getOrDefault(b.item().bookingId(),
                        List.of()))))
                .toList());
        Map<InterventionId, List<CompletionView>> completions = grouped(
                jdbc.sql(COMPLETIONS).param("ids", ids).query(JdbcInterventionReadModel::completionOf).list());
        return rows.stream().map(row -> view(row, proposals.getOrDefault(row.id(), List.of()),
                bookings.getOrDefault(row.id(), List.of()), completions.getOrDefault(row.id(), List.of()))).toList();
    }

    /** One child row and the intervention it belongs to. */
    private record Owned<T>(InterventionId interventionId, T item) {
    }

    /** Groups child rows by intervention, keeping the query's order within each. */
    private static <T> Map<InterventionId, List<T>> grouped(List<Owned<T>> owned) {
        return owned.stream().collect(Collectors.groupingBy(Owned::interventionId, LinkedHashMap::new,
                Collectors.mapping(Owned::item, Collectors.toList())));
    }

    private Map<BookingId, List<BookingView.Call>> calls(List<BookingId> bookingIds) {
        if (bookingIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql(CALLS).param("ids", bookingIds.stream().map(BookingId::toString).toList())
                .query((rs, n) -> Map.entry(new BookingId(UUID.fromString(rs.getString("booking_id"))),
                        new BookingView.Call(BookingAction.fromValue(rs.getString("action")), rs.getString("outcome"),
                                time(rs, "occurred_at"))))
                .list()
                .stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    private static InterventionView view(
            Row row, List<InterventionView.Proposal> proposals, List<BookingView> bookings,
            List<CompletionView> completions) {
        InterventionView.Proposal current = proposals.stream()
                .filter(p -> p.proposalVersion() == row.currentProposalVersion())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the current proposal version is missing"));
        return new InterventionView(row.id(), row.issueId(), row.buildingId(), row.contractor(), row.state(),
                row.version(), current, proposals, row.approvedProposalVersion(), row.agreedProposalVersion(),
                row.contractorContacted(), row.cancelReason(), row.cancelNote(), bookings, completions,
                row.createdAt(), row.updatedAt());
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
                Optional.ofNullable(rs.getString("cancel_reason_code")).flatMap(CancelReason::fromValue),
                Optional.ofNullable(rs.getString("cancel_note")),
                time(rs, "created_at"),
                time(rs, "updated_at"));
    }

    private static Owned<CompletionView> completionOf(ResultSet rs, int n) throws SQLException {
        return new Owned<>(interventionOf(rs), new CompletionView(
                rs.getInt("number"),
                rs.getString("summary"),
                time(rs, "performed_at"),
                rs.getString("reporter_role"),
                Optional.ofNullable(rs.getString("reason")),
                time(rs, "reported_at"),
                Optional.ofNullable(rs.getString("rework_reason")),
                Optional.ofNullable(rs.getObject("rework_at", LocalDateTime.class)).map(Timestamps::fromColumn)));
    }

    private static Owned<BookingView> bookingOf(ResultSet rs, int n) throws SQLException {
        return new Owned<>(interventionOf(rs), booking(rs, n));
    }

    private static InterventionId interventionOf(ResultSet rs) throws SQLException {
        return new InterventionId(UUID.fromString(rs.getString("intervention_id")));
    }

    private static BookingView withCalls(BookingView booking, List<BookingView.Call> calls) {
        return new BookingView(booking.bookingId(), booking.state(), booking.source(), booking.requestedWindow(),
                booking.bookedWindow(), booking.externalRef(), booking.reasonCode(), booking.pendingAction(),
                booking.lastReconciledAt(), booking.manual(), calls, booking.createdAt(), booking.updatedAt());
    }

    private static BookingView booking(ResultSet rs, int n) throws SQLException {
        TimeWindow requested = new TimeWindow(time(rs, "window_start"), time(rs, "window_end"));
        Optional<Instant> manualAt = Optional.ofNullable(rs.getObject("manual_at", LocalDateTime.class))
                .map(Timestamps::fromColumn);
        Optional<BookingView.Manual> manual = manualAt.isEmpty()
                ? Optional.empty()
                : Optional.of(new BookingView.Manual(ManualSource.fromValue(rs.getString("manual_source")),
                        rs.getString("manual_reason"), manualAt.get()));
        TimeWindow booked = manualAt.isEmpty()
                ? requested
                : new TimeWindow(time(rs, "manual_window_start"), time(rs, "manual_window_end"));
        return new BookingView(
                new BookingId(UUID.fromString(rs.getString("id"))),
                BookingState.fromValue(rs.getString("state")),
                Optional.ofNullable(rs.getString("source")).map(BookingSource::fromValue),
                requested,
                booked,
                Optional.ofNullable(rs.getString("external_ref")),
                Optional.ofNullable(rs.getString("reason_code")),
                Optional.ofNullable(rs.getString("pending_action")).map(BookingAction::fromValue),
                Optional.ofNullable(rs.getObject("last_reconciled_at", LocalDateTime.class)).map(Timestamps::fromColumn),
                manual,
                List.of(),
                time(rs, "created_at"),
                time(rs, "updated_at"));
    }

    private static Owned<InterventionView.Proposal> proposalOf(ResultSet rs, int n) throws SQLException {
        return new Owned<>(interventionOf(rs), proposal(rs, n));
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
