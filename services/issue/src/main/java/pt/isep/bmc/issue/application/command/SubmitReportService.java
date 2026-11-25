package pt.isep.bmc.issue.application.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import pt.isep.bmc.issue.application.idempotency.IdempotentExecutor;
import pt.isep.bmc.issue.application.port.in.SubmitReportUseCase;
import pt.isep.bmc.issue.application.port.out.BuildingRepository;
import pt.isep.bmc.issue.application.port.out.EventOutbox;
import pt.isep.bmc.issue.application.port.out.IdGenerator;
import pt.isep.bmc.issue.application.port.out.IdempotencyRecord;
import pt.isep.bmc.issue.application.port.out.IdempotencyScope;
import pt.isep.bmc.issue.application.port.out.IdempotencyStore;
import pt.isep.bmc.issue.application.port.out.IssueRepository;
import pt.isep.bmc.issue.application.port.out.MembershipRepository;
import pt.isep.bmc.issue.application.port.out.OutboxEvent;
import pt.isep.bmc.issue.application.port.out.ReportRepository;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;
import pt.isep.bmc.issue.domain.event.DomainEvent;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.membership.BuildingAccess;
import pt.isep.bmc.issue.domain.report.Report;
import pt.isep.bmc.issue.domain.report.ReportId;
import pt.isep.bmc.issue.domain.report.ReportIntake;
import pt.isep.bmc.issue.domain.report.ReportSubmission;

/**
 * Stores a report, its new issue, the first history entry, the
 * {@code ReportSubmitted} and {@code TriageRequested} outbox rows and the
 * idempotency record in <b>one</b> transaction (technical design 3.1 and 5).
 * The request never waits for AI or the broker.
 *
 * <p>Idempotent per key (technical design 3.3): see {@link IdempotentExecutor}.
 * The key is the first write of the transaction, after the domain has
 * accepted the report.
 */
public final class SubmitReportService implements SubmitReportUseCase {

    public static final String OPERATION = "POST /reports";
    static final Duration KEY_RETENTION = Duration.ofHours(24);
    private static final int CREATED = 201;

    private final TransactionRunner transactions;
    private final MembershipRepository memberships;
    private final BuildingRepository buildings;
    private final IssueRepository issues;
    private final ReportRepository reports;
    private final EventOutbox outbox;
    private final IdempotencyStore idempotency;
    private final IdempotentExecutor idempotent;
    private final IdGenerator ids;
    private final Clock clock;

    public SubmitReportService(
            TransactionRunner transactions,
            MembershipRepository memberships,
            BuildingRepository buildings,
            IssueRepository issues,
            ReportRepository reports,
            EventOutbox outbox,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        this.transactions = transactions;
        this.memberships = memberships;
        this.buildings = buildings;
        this.issues = issues;
        this.reports = reports;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.idempotent = new IdempotentExecutor(transactions, idempotency);
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public ReportReceipt submit(SubmitReportCommand command) {
        IdempotencyScope scope = new IdempotencyScope(command.actor(), OPERATION, command.idempotencyKey());
        String requestHash = fingerprint(command);
        // Millisecond precision, so a replay returns exactly the stored time.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return idempotent.execute(scope, requestHash, now, () -> create(command, scope, requestHash, now), this::replay);
    }

    private ReportReceipt create(SubmitReportCommand command, IdempotencyScope scope, String requestHash, Instant now) {
        BuildingAccess access = BuildingAccess.of(command.actor(), memberships.findByUser(command.actor()), now);
        ReportSubmission submission = ReportIntake.submit(
                access,
                command.buildingId(),
                command.areaId(),
                buildings.findArea(command.areaId()),
                command.text(),
                new ReportId(ids.newId()),
                new IssueId(ids.newId()),
                now);
        Issue issue = submission.issue();
        Report report = submission.report();

        // First write: the key's primary key serialises concurrent retries. A retry
        // waits here until this transaction ends, then fails as a duplicate.
        idempotency.record(new IdempotencyRecord(
                scope, requestHash, CREATED, report.id().value(), issue.version(), now, now.plus(KEY_RETENTION)));

        issues.add(issue);
        reports.add(report);
        issues.appendHistory(submission.history());
        for (DomainEvent event : submission.events()) {
            outbox.append(new OutboxEvent(ids.newId(), event, command.correlationId()));
        }
        return new ReportReceipt(report.id(), issue.id(), issue.state(), issue.version(), report.submittedAt(), false);
    }

    private ReportReceipt replay(IdempotencyRecord record) {
        Report report = reports.findById(new ReportId(record.resourceId()))
                .orElseThrow(() -> new IllegalStateException("idempotency record points at a missing report"));
        // A report's receipt always describes its creation: received, at the stored version.
        return new ReportReceipt(
                report.id(), report.issueId(), IssueState.RECEIVED, record.resourceVersion(), report.submittedAt(), true);
    }

    /** SHA-256 of the request fields exactly as received. */
    static String fingerprint(SubmitReportCommand command) {
        return IdempotentExecutor.fingerprint(command.buildingId(), command.areaId(), command.text());
    }
}
