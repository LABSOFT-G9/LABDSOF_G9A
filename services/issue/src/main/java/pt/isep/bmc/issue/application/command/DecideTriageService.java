package pt.isep.bmc.issue.application.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.application.idempotency.IdempotentExecutor;
import pt.isep.bmc.issue.application.port.in.DecideTriageUseCase;
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
import pt.isep.bmc.issue.application.port.out.TriageDecisionRepository;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.event.DomainEvent;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.issue.IssueTriage;
import pt.isep.bmc.issue.domain.issue.TriageDecision;
import pt.isep.bmc.issue.domain.issue.TriageOutcome;
import pt.isep.bmc.issue.domain.membership.BuildingAccess;

/**
 * Stores a triage decision, the issue's next version, the history entry, the
 * {@code IssueTriaged} and {@code IssueStateChanged} outbox rows and the
 * idempotency record in <b>one</b> transaction (technical design 3.1 and 5).
 * A rejected decision stores nothing.
 *
 * <p>Two rules keep concurrent requests correct:
 * <ul>
 *   <li>the idempotency key is the first write (see {@link IdempotentExecutor}),
 *       so a retry of a decision that already committed is replayed even
 *       though the issue's version has moved on. The replay checks the
 *       caller's permissions again;</li>
 *   <li>the issue row is then locked, so of two administrators who decide
 *       from the same version, the second sees the first one's version and
 *       gets {@code VERSION_CONFLICT}.</li>
 * </ul>
 * Every request takes the two locks in that order, so they cannot deadlock.
 */
public final class DecideTriageService implements DecideTriageUseCase {

    public static final String OPERATION = "POST /issues/{id}/triage";
    static final Duration KEY_RETENTION = Duration.ofHours(24);
    private static final int OK = 200;

    private final TransactionRunner transactions;
    private final MembershipRepository memberships;
    private final BuildingRepository buildings;
    private final IssueRepository issues;
    private final ReportRepository reports;
    private final TriageDecisionRepository decisions;
    private final EventOutbox outbox;
    private final IdempotencyStore idempotency;
    private final IdempotentExecutor idempotent;
    private final IdGenerator ids;
    private final Clock clock;

    public DecideTriageService(
            TransactionRunner transactions,
            MembershipRepository memberships,
            BuildingRepository buildings,
            IssueRepository issues,
            ReportRepository reports,
            TriageDecisionRepository decisions,
            EventOutbox outbox,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        this.transactions = transactions;
        this.memberships = memberships;
        this.buildings = buildings;
        this.issues = issues;
        this.reports = reports;
        this.decisions = decisions;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.idempotent = new IdempotentExecutor(transactions, idempotency);
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public TriageReceipt decide(DecideTriageCommand command) {
        IdempotencyScope scope = new IdempotencyScope(command.actor(), OPERATION, command.idempotencyKey());
        String requestHash = IdempotentExecutor.fingerprint(
                command.issueId(), command.expectedVersion(), command.category(), command.urgency(), command.reason());
        // Millisecond precision, so the stored and the returned times are the same.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return idempotent.execute(scope, requestHash, now, () -> apply(command, scope, requestHash, now),
                record -> replay(command, record, now));
    }

    private TriageReceipt apply(DecideTriageCommand command, IdempotencyScope scope, String requestHash, Instant now) {
        // First write, before the issue is even read: a retry with the same key waits
        // here. A decision is only stored from the expected version, so its resulting
        // version is known already. Any rejection below rolls this row back.
        idempotency.record(new IdempotencyRecord(scope, requestHash, OK, command.issueId().value(),
                command.expectedVersion() + 1, now, now.plus(KEY_RETENTION)));

        Issue issue = issues.findForUpdate(command.issueId()).orElseThrow(NotFoundException::new);
        BuildingAccess access = BuildingAccess.of(command.actor(), memberships.findByUser(command.actor()), now);
        String areaLabel = buildings.findArea(issue.areaId()).map(Area::label)
                .orElseThrow(() -> new IllegalStateException("issue points at a missing area"));

        TriageOutcome outcome = IssueTriage.decide(
                access,
                issue,
                reports.existsByIssueAndAuthor(issue.id(), command.actor()),
                command.expectedVersion(),
                command.category(),
                command.urgency(),
                command.reason(),
                issues.nextHistorySequence(issue.id()),
                areaLabel,
                now);

        issues.update(outcome.issue());
        decisions.add(outcome.decision());
        issues.appendHistory(outcome.history());
        for (DomainEvent event : outcome.events()) {
            outbox.append(new OutboxEvent(ids.newId(), event, command.correlationId()));
        }
        return receipt(outcome.decision(), false);
    }

    /**
     * The stored receipt, for a caller who may still decide this issue's triage.
     * The key proves who asked first, not what that user may see now: a revoked
     * administrator gets the same refusal as for a new decision.
     */
    private TriageReceipt replay(DecideTriageCommand command, IdempotencyRecord record, Instant now) {
        IssueId issueId = new IssueId(record.resourceId());
        Issue issue = issues.find(issueId)
                .orElseThrow(() -> new IllegalStateException("idempotency record points at a missing issue"));
        IssueTriage.authorise(
                BuildingAccess.of(command.actor(), memberships.findByUser(command.actor()), now),
                issue,
                reports.existsByIssueAndAuthor(issueId, command.actor()));
        TriageDecision decision = decisions.find(issueId, record.resourceVersion())
                .orElseThrow(() -> new IllegalStateException("idempotency record points at a missing decision"));
        return receipt(decision, true);
    }

    /** A decision's receipt always describes the decision: triaged, at its own version. */
    private static TriageReceipt receipt(TriageDecision decision, boolean replayed) {
        return new TriageReceipt(decision.issueId(), IssueState.TRIAGED, decision.resultingVersion(),
                decision.category(), decision.urgency(), replayed);
    }
}
