package pt.isep.bmc.dispatch.application.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.application.idempotency.IdempotentExecutor;
import pt.isep.bmc.dispatch.application.port.in.ExecutionUseCases;
import pt.isep.bmc.dispatch.application.port.out.CompletionReportRepository;
import pt.isep.bmc.dispatch.application.port.out.ContractorCatalogue;
import pt.isep.bmc.dispatch.application.port.out.EventOutbox;
import pt.isep.bmc.dispatch.application.port.out.IdGenerator;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyRecord;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyScope;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyStore;
import pt.isep.bmc.dispatch.application.port.out.InterventionRepository;
import pt.isep.bmc.dispatch.application.port.out.IssueProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.MembershipProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.OutboxEvent;
import pt.isep.bmc.dispatch.application.port.out.TransactionRunner;
import pt.isep.bmc.dispatch.domain.event.CompletionReported;
import pt.isep.bmc.dispatch.domain.event.DomainEvent;
import pt.isep.bmc.dispatch.domain.event.InterventionStarted;
import pt.isep.bmc.dispatch.domain.event.ReworkRequested;
import pt.isep.bmc.dispatch.domain.intervention.CompletionReport;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionAgreement;
import pt.isep.bmc.dispatch.domain.intervention.InterventionExecution;
import pt.isep.bmc.dispatch.domain.intervention.InterventionExecution.CompletionOutcome;
import pt.isep.bmc.dispatch.domain.intervention.InterventionExecution.CompletionRequest;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.projection.AdministratorAccess;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * Start, completion and rework (US-09), each with {@code If-Match} and an
 * idempotency key, and its event in the same transaction: {@code
 * InterventionStarted}, {@code CompletionReported}, {@code ReworkRequested}.
 * Reporting completion moves the issue to awaiting validation through the
 * event; it never closes it.
 *
 * <p>Locks the issue projection row, then the intervention, as every other
 * command does. A completion report is written under the intervention's lock.
 */
public final class ExecutionService implements ExecutionUseCases {

    public static final String START_OPERATION = "POST /interventions/{id}/start";
    public static final String COMPLETION_OPERATION = "POST /interventions/{id}/completion";
    public static final String REWORK_OPERATION = "POST /interventions/{id}/rework";
    static final Duration KEY_RETENTION = Duration.ofHours(24);
    private static final int OK = 200;

    private final IssueProjectionRepository issues;
    private final MembershipProjectionRepository memberships;
    private final ContractorCatalogue catalogue;
    private final InterventionRepository interventions;
    private final CompletionReportRepository completions;
    private final EventOutbox outbox;
    private final IdempotencyStore idempotency;
    private final IdempotentExecutor idempotent;
    private final IdGenerator ids;
    private final Clock clock;

    public ExecutionService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            MembershipProjectionRepository memberships,
            ContractorCatalogue catalogue,
            InterventionRepository interventions,
            CompletionReportRepository completions,
            EventOutbox outbox,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        this.issues = issues;
        this.memberships = memberships;
        this.catalogue = catalogue;
        this.interventions = interventions;
        this.completions = completions;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.idempotent = new IdempotentExecutor(transactions, idempotency);
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public ExecutionResult start(StartCommand command) {
        Instant now = now();
        return run(command.actor(), START_OPERATION, command.idempotencyKey(), command.interventionId(),
                command.expectedVersion(), now, IdempotentExecutor.fingerprint(command.interventionId(),
                        command.expectedVersion()), () -> {
                    Intervention unlocked = find(command.interventionId());
                    requireContractor(command.actor(), unlocked);
                    Locked locked = lock(unlocked);
                    Intervention started = InterventionExecution.start(locked.intervention(), locked.issue(),
                            command.expectedVersion(), now);
                    interventions.update(started);
                    append(new InterventionStarted(started.id().value(), started.version(), started.buildingId(), now,
                            started.issueId(), started.state()), command.correlationId());
                    return started;
                });
    }

    @Override
    public ExecutionResult reportCompletion(CompletionCommand command) {
        Instant now = now();
        String hash = IdempotentExecutor.fingerprint(command.interventionId(), command.expectedVersion(),
                command.summary(), command.performedAt(), command.reason());
        return run(command.actor(), COMPLETION_OPERATION, command.idempotencyKey(), command.interventionId(),
                command.expectedVersion(), now, hash, () -> {
                    Intervention unlocked = find(command.interventionId());
                    String role = role(command.actor(), unlocked);
                    CompletionRequest request = InterventionExecution.validateCompletion(command.summary(),
                            command.performedAt(), command.reason(),
                            CompletionReport.BY_ADMINISTRATOR.equals(role), now);
                    Locked locked = lock(unlocked);
                    int number = completions.findLatest(unlocked.id()).map(r -> r.number() + 1).orElse(1);
                    CompletionOutcome outcome = InterventionExecution.reportCompletion(locked.intervention(),
                            locked.issue(), request, role, number, command.actor(), command.expectedVersion(), now);
                    Intervention reported = outcome.intervention();
                    interventions.update(reported);
                    completions.add(outcome.report());
                    append(new CompletionReported(reported.id().value(), reported.version(), reported.buildingId(),
                            now, reported.issueId(), reported.state()), command.correlationId());
                    return reported;
                });
    }

    @Override
    public ExecutionResult requestRework(ReworkCommand command) {
        Instant now = now();
        String hash = IdempotentExecutor.fingerprint(command.interventionId(), command.expectedVersion(),
                command.reason());
        return run(command.actor(), REWORK_OPERATION, command.idempotencyKey(), command.interventionId(),
                command.expectedVersion(), now, hash, () -> {
                    Intervention unlocked = find(command.interventionId());
                    administrator(command.actor()).requireAdministers(unlocked.buildingId());
                    String reason = InterventionExecution.validateRework(command.reason());
                    Locked locked = lock(unlocked);
                    CompletionReport latest = completions.findLatest(unlocked.id())
                            .orElseThrow(() -> new InvalidTransitionException(locked.intervention().state().value()));
                    CompletionOutcome outcome = InterventionExecution.requestRework(locked.intervention(),
                            locked.issue(), latest, reason, command.actor(), command.expectedVersion(), now);
                    Intervention reworked = outcome.intervention();
                    interventions.update(reworked);
                    completions.recordRework(outcome.report());
                    append(new ReworkRequested(reworked.id().value(), reworked.version(), reworked.buildingId(), now,
                            reworked.issueId(), reworked.state()), command.correlationId());
                    return reworked;
                });
    }

    /** The key first, then the command; a replay re-checks that the caller may still act on the intervention. */
    private ExecutionResult run(
            UserId actor, String operation, IdempotencyKey key, InterventionId id, long expectedVersion, Instant now,
            String hash, Supplier<Intervention> command) {
        IdempotencyScope scope = new IdempotencyScope(actor, operation, key);
        return idempotent.execute(scope, hash, now, () -> {
            idempotency.record(new IdempotencyRecord(scope, hash, OK, id.value(), expectedVersion + 1, now,
                    now.plus(KEY_RETENTION)));
            Intervention changed = command.get();
            return new ExecutionResult(changed.id(), changed.state(), changed.version(), false);
        }, record -> {
            Intervention intervention = find(id);
            role(actor, intervention);
            return new ExecutionResult(id, replayedState(operation), record.resourceVersion(), true);
        });
    }

    private static InterventionState replayedState(String operation) {
        return switch (operation) {
            case START_OPERATION, REWORK_OPERATION -> InterventionState.IN_PROGRESS;
            default -> InterventionState.COMPLETION_REPORTED;
        };
    }

    /** The assigned contractor, or else an administrator of the building; anyone else gets {@code 404}. */
    private String role(UserId actor, Intervention intervention) {
        try {
            requireContractor(actor, intervention);
            return CompletionReport.BY_CONTRACTOR;
        } catch (AccessDeniedException notTheContractor) {
            administrator(actor).requireAdministers(intervention.buildingId());
            return CompletionReport.BY_ADMINISTRATOR;
        }
    }

    private void requireContractor(UserId actor, Intervention intervention) {
        InterventionAgreement.requireAssignedContractor(actor, intervention, catalogue.find(intervention.contractorId()));
    }

    private AdministratorAccess administrator(UserId actor) {
        return AdministratorAccess.of(actor, memberships.findByUser(actor));
    }

    private Intervention find(InterventionId id) {
        return interventions.find(id).orElseThrow(AccessDeniedException::new);
    }

    private record Locked(ProjectedIssue issue, Intervention intervention) {
    }

    private Locked lock(Intervention unlocked) {
        ProjectedIssue issue = issues.findForUpdate(unlocked.issueId())
                .orElseThrow(() -> new IllegalStateException("intervention points at an issue missing from the projection"));
        Intervention current = interventions.findForUpdate(unlocked.id()).orElseThrow(AccessDeniedException::new);
        return new Locked(issue, current);
    }

    private void append(DomainEvent event, String correlationId) {
        outbox.append(new OutboxEvent(ids.newId(), event, correlationId));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
