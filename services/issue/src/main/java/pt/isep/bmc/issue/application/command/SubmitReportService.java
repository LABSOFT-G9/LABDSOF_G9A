package pt.isep.bmc.issue.application.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import pt.isep.bmc.issue.application.error.IdempotencyKeyReusedException;
import pt.isep.bmc.issue.application.port.in.SubmitReportUseCase;
import pt.isep.bmc.issue.application.port.out.BuildingRepository;
import pt.isep.bmc.issue.application.port.out.DuplicateIdempotencyKeyException;
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
 * <p>Idempotency (technical design 3.3): the key is the first write of the
 * transaction. A concurrent retry with the same key blocks on that unique row
 * until the first transaction ends; if it committed, the retry fails with
 * {@link DuplicateIdempotencyKeyException}, rolls back everything it did and
 * replays the stored receipt. A different body under the same key is rejected
 * with {@link IdempotencyKeyReusedException} and changes nothing.
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
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public ReportReceipt submit(SubmitReportCommand command) {
        IdempotencyScope scope = new IdempotencyScope(command.actor(), OPERATION, command.idempotencyKey());
        String requestHash = fingerprint(command);
        // Millisecond precision, so a replay returns exactly the stored time.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);

        Optional<IdempotencyRecord> stored = transactions.readOnly(() -> idempotency.find(scope));
        if (stored.isPresent() && !stored.get().isExpiredAt(now)) {
            return transactions.readOnly(() -> replay(stored.get(), requestHash));
        }
        if (stored.isPresent()) {
            // Expired: free the key in its own short transaction. Deleting inside the
            // create transaction would take InnoDB gap locks that deadlock concurrent inserts.
            transactions.inTransaction(() -> {
                idempotency.deleteIfExpired(scope, now);
                return null;
            });
        }
        try {
            return transactions.inTransaction(() -> create(command, scope, requestHash, now));
        } catch (DuplicateIdempotencyKeyException concurrentRetry) {
            return transactions.readOnly(() -> replay(
                    idempotency.find(scope)
                            .filter(r -> !r.isExpiredAt(now))
                            .orElseThrow(() -> new IllegalStateException("duplicate key without a live record")),
                    requestHash));
        }
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

    private ReportReceipt replay(IdempotencyRecord record, String requestHash) {
        if (!MessageDigest.isEqual(
                record.requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new IdempotencyKeyReusedException();
        }
        Report report = reports.findById(new ReportId(record.resourceId()))
                .orElseThrow(() -> new IllegalStateException("idempotency record points at a missing report"));
        // A report's receipt always describes its creation: received, at the stored version.
        return new ReportReceipt(
                report.id(), report.issueId(), IssueState.RECEIVED, record.resourceVersion(), report.submittedAt(), true);
    }

    /** SHA-256 of the request fields exactly as received. */
    static String fingerprint(SubmitReportCommand command) {
        String canonical = "v1\n" + command.buildingId() + "\n" + command.areaId() + "\n"
                + (command.text() == null ? "" : command.text());
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
