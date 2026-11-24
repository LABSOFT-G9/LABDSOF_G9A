package pt.isep.bmc.issue.application.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.application.idempotency.IdempotentExecutor;
import pt.isep.bmc.issue.application.port.in.PublishSummaryUseCase;
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
import pt.isep.bmc.issue.application.port.out.SummaryRepository;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.event.DomainEvent;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueSharing;
import pt.isep.bmc.issue.domain.issue.IssueSummary;
import pt.isep.bmc.issue.domain.issue.PublicationOutcome;
import pt.isep.bmc.issue.domain.issue.SummaryId;
import pt.isep.bmc.issue.domain.membership.BuildingAccess;

/**
 * Stores a published summary, the issue's next version, the history entry, the
 * {@code IssueSummaryPublished} and {@code IssueStateChanged} outbox rows and
 * the idempotency record in <b>one</b> transaction (technical design 5). A
 * rejected publication stores nothing.
 *
 * <p>Same two rules as a triage decision ({@link DecideTriageService}): the
 * idempotency key is the first write, so a retry of a publication that already
 * committed is replayed although the version has moved on; then the issue row
 * is locked, so of two administrators who publish from the same version the
 * second gets {@code VERSION_CONFLICT}.
 */
public final class PublishSummaryService implements PublishSummaryUseCase {

    public static final String OPERATION = "POST /issues/{id}/publication";
    static final Duration KEY_RETENTION = Duration.ofHours(24);
    private static final int OK = 200;

    private final TransactionRunner transactions;
    private final MembershipRepository memberships;
    private final BuildingRepository buildings;
    private final IssueRepository issues;
    private final ReportRepository reports;
    private final SummaryRepository summaries;
    private final EventOutbox outbox;
    private final IdempotencyStore idempotency;
    private final IdempotentExecutor idempotent;
    private final IdGenerator ids;
    private final Clock clock;

    public PublishSummaryService(
            TransactionRunner transactions,
            MembershipRepository memberships,
            BuildingRepository buildings,
            IssueRepository issues,
            ReportRepository reports,
            SummaryRepository summaries,
            EventOutbox outbox,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        this.transactions = transactions;
        this.memberships = memberships;
        this.buildings = buildings;
        this.issues = issues;
        this.reports = reports;
        this.summaries = summaries;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.idempotent = new IdempotentExecutor(transactions, idempotency);
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public PublicationReceipt publish(PublishSummaryCommand command) {
        IdempotencyScope scope = new IdempotencyScope(command.actor(), OPERATION, command.idempotencyKey());
        String requestHash =
                IdempotentExecutor.fingerprint(command.issueId(), command.expectedVersion(), command.summary());
        // Millisecond precision, so the stored and the returned times are the same.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return idempotent.execute(scope, requestHash, now, () -> apply(command, scope, requestHash, now), this::replay);
    }

    private PublicationReceipt apply(
            PublishSummaryCommand command, IdempotencyScope scope, String requestHash, Instant now) {
        // First write, as for triage: the resulting version is known before the issue is read.
        idempotency.record(new IdempotencyRecord(scope, requestHash, OK, command.issueId().value(),
                command.expectedVersion() + 1, now, now.plus(KEY_RETENTION)));

        Issue issue = issues.findForUpdate(command.issueId()).orElseThrow(NotFoundException::new);
        BuildingAccess access = BuildingAccess.of(command.actor(), memberships.findByUser(command.actor()), now);
        String areaLabel = buildings.findArea(issue.areaId()).map(Area::label)
                .orElseThrow(() -> new IllegalStateException("issue points at a missing area"));

        PublicationOutcome outcome = IssueSharing.publish(
                access,
                issue,
                reports.existsByIssueAndAuthor(issue.id(), command.actor()),
                command.expectedVersion(),
                command.summary(),
                new SummaryId(ids.newId()),
                issues.nextHistorySequence(issue.id()),
                areaLabel,
                now);

        // The summary first: the issue row points at it.
        summaries.add(outcome.summary());
        issues.update(outcome.issue());
        issues.appendHistory(outcome.history());
        for (DomainEvent event : outcome.events()) {
            outbox.append(new OutboxEvent(ids.newId(), event, command.correlationId()));
        }
        return receipt(outcome.summary(), false);
    }

    private PublicationReceipt replay(IdempotencyRecord record) {
        IssueSummary summary = summaries.find(new IssueId(record.resourceId()), record.resourceVersion())
                .orElseThrow(() -> new IllegalStateException("idempotency record points at a missing summary"));
        return receipt(summary, true);
    }

    private static PublicationReceipt receipt(IssueSummary summary, boolean replayed) {
        return new PublicationReceipt(summary.issueId(), summary.resultingVersion(), summary.text(),
                summary.publishedAt(), replayed);
    }
}
