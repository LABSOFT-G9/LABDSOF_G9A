package pt.isep.bmc.dispatch.application.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import pt.isep.bmc.dispatch.application.idempotency.IdempotentExecutor;
import pt.isep.bmc.dispatch.application.port.in.ReplaceContractorUseCase;
import pt.isep.bmc.dispatch.application.port.out.ContractorCatalogue;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyRecord;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyScope;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyStore;
import pt.isep.bmc.dispatch.application.port.out.InterventionRepository;
import pt.isep.bmc.dispatch.application.port.out.IssueProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.MembershipProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.TransactionRunner;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionDrafting;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.projection.AdministratorAccess;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;

/**
 * Replaces the contractor of a draft request (US-06), with {@code If-Match}
 * and an idempotency key. Stores nothing when a check fails.
 *
 * <p>Locks the issue projection row before the intervention row, in the same
 * order as a new draft and the Issue event consumer, so the three never
 * deadlock and never interleave.
 */
public final class ReplaceContractorService implements ReplaceContractorUseCase {

    public static final String OPERATION = "PATCH /interventions/{id}";
    static final Duration KEY_RETENTION = Duration.ofHours(24);
    private static final int OK = 200;

    private final TransactionRunner transactions;
    private final IssueProjectionRepository issues;
    private final MembershipProjectionRepository memberships;
    private final ContractorCatalogue catalogue;
    private final InterventionRepository interventions;
    private final IdempotencyStore idempotency;
    private final IdempotentExecutor idempotent;
    private final Clock clock;

    public ReplaceContractorService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            MembershipProjectionRepository memberships,
            ContractorCatalogue catalogue,
            InterventionRepository interventions,
            IdempotencyStore idempotency,
            Clock clock) {
        this.transactions = transactions;
        this.issues = issues;
        this.memberships = memberships;
        this.catalogue = catalogue;
        this.interventions = interventions;
        this.idempotency = idempotency;
        this.idempotent = new IdempotentExecutor(transactions, idempotency);
        this.clock = clock;
    }

    @Override
    public ContractorReplaced replace(ReplaceContractorCommand command) {
        IdempotencyScope scope = new IdempotencyScope(command.actor(), OPERATION, command.idempotencyKey());
        String requestHash = IdempotentExecutor.fingerprint(
                command.interventionId(), command.expectedVersion(), command.contractorId());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return idempotent.execute(scope, requestHash, now, () -> apply(command, scope, requestHash, now),
                record -> replay(command, record));
    }

    private ContractorReplaced apply(
            ReplaceContractorCommand command, IdempotencyScope scope, String requestHash, Instant now) {
        idempotency.record(new IdempotencyRecord(scope, requestHash, OK, command.interventionId().value(),
                command.expectedVersion() + 1, now, now.plus(KEY_RETENTION)));

        Intervention unlocked = interventions.find(command.interventionId()).orElseThrow(AccessDeniedException::new);
        AdministratorAccess access = access(command);
        access.requireAdministers(unlocked.buildingId());
        ContractorId contractorId = InterventionDrafting.parseContractorId(command.contractorId());

        ProjectedIssue issue = issues.findForUpdate(unlocked.issueId())
                .orElseThrow(() -> new IllegalStateException("intervention points at an issue missing from the projection"));
        Intervention current = interventions.findForUpdate(command.interventionId())
                .orElseThrow(AccessDeniedException::new);
        Intervention replaced = InterventionDrafting.replaceContractor(access, current, issue,
                command.expectedVersion(), contractorId, catalogue.find(contractorId), now);
        interventions.update(replaced);
        return new ContractorReplaced(replaced.id(), replaced.state(), replaced.version(), replaced.contractorId(),
                false);
    }

    /** The original result, for a caller who still administers the building. */
    private ContractorReplaced replay(ReplaceContractorCommand command, IdempotencyRecord record) {
        Intervention intervention = interventions.find(command.interventionId())
                .orElseThrow(() -> new IllegalStateException("idempotency record points at a missing intervention"));
        access(command).requireAdministers(intervention.buildingId());
        return new ContractorReplaced(intervention.id(), InterventionState.AWAITING_APPROVAL, record.resourceVersion(),
                InterventionDrafting.parseContractorId(command.contractorId()), true);
    }

    private AdministratorAccess access(ReplaceContractorCommand command) {
        return AdministratorAccess.of(command.actor(), memberships.findByUser(command.actor()));
    }
}
