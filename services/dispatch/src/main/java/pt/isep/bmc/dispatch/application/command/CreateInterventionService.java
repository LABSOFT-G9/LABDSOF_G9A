package pt.isep.bmc.dispatch.application.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import pt.isep.bmc.dispatch.application.idempotency.IdempotentExecutor;
import pt.isep.bmc.dispatch.application.port.in.CreateInterventionUseCase;
import pt.isep.bmc.dispatch.application.port.out.ContractorCatalogue;
import pt.isep.bmc.dispatch.application.port.out.IdGenerator;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyRecord;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyScope;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyStore;
import pt.isep.bmc.dispatch.application.port.out.InterventionRepository;
import pt.isep.bmc.dispatch.application.port.out.IssueProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.MembershipProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.TransactionRunner;
import pt.isep.bmc.dispatch.domain.contractor.Contractor;
import pt.isep.bmc.dispatch.domain.intervention.DraftOutcome;
import pt.isep.bmc.dispatch.domain.intervention.DraftTerms;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionDrafting;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.projection.AdministratorAccess;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;

/**
 * Saves a draft request (US-06): the intervention in {@code
 * awaiting_approval}, version 1, with proposal version 1, and the idempotency
 * record, in <b>one</b> transaction. Nothing is sent and no event is written:
 * selection alone confirms neither acceptance nor a booking.
 *
 * <p>The idempotency key is the first write, so a retry of a draft that
 * already committed is replayed. Then the issue projection row is locked
 * (technical design 4.3 step 8): two drafts for the same issue, or a draft
 * and an Issue event, are serialised, and the second draft sees the first as
 * the issue's active intervention. A unique index on the active intervention
 * per issue backs this up in the database.
 */
public final class CreateInterventionService implements CreateInterventionUseCase {

    public static final String OPERATION = "POST /interventions";
    static final Duration KEY_RETENTION = Duration.ofHours(24);
    private static final int CREATED = 201;

    private final TransactionRunner transactions;
    private final IssueProjectionRepository issues;
    private final MembershipProjectionRepository memberships;
    private final ContractorCatalogue catalogue;
    private final InterventionRepository interventions;
    private final IdempotencyStore idempotency;
    private final IdempotentExecutor idempotent;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateInterventionService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            MembershipProjectionRepository memberships,
            ContractorCatalogue catalogue,
            InterventionRepository interventions,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        this.transactions = transactions;
        this.issues = issues;
        this.memberships = memberships;
        this.catalogue = catalogue;
        this.interventions = interventions;
        this.idempotency = idempotency;
        this.idempotent = new IdempotentExecutor(transactions, idempotency);
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public DraftReceipt create(CreateInterventionCommand command) {
        IdempotencyScope scope = new IdempotencyScope(command.actor(), OPERATION, command.idempotencyKey());
        String requestHash = IdempotentExecutor.fingerprint(command.issueId(), command.contractorId(),
                command.scopeExtract(), command.windowStart(), command.windowEnd(), command.terms());
        // Millisecond precision, so the stored and the returned times are the same.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        InterventionId newId = new InterventionId(ids.newId());
        return idempotent.execute(scope, requestHash, now, () -> apply(command, scope, requestHash, newId, now),
                record -> replay(command, record));
    }

    private DraftReceipt apply(
            CreateInterventionCommand command, IdempotencyScope scope, String requestHash, InterventionId newId,
            Instant now) {
        idempotency.record(new IdempotencyRecord(scope, requestHash, CREATED, newId.value(),
                Intervention.INITIAL_VERSION, now, now.plus(KEY_RETENTION)));

        ProjectedIssue issue = issues.findForUpdate(command.issueId()).orElseThrow(AccessDeniedException::new);
        AdministratorAccess access = access(command);
        InterventionDrafting.authorise(access, issue);
        DraftTerms terms = InterventionDrafting.validate(command.contractorId(), command.scopeExtract(),
                command.windowStart(), command.windowEnd(), command.terms(), now);
        Optional<Contractor> contractor = catalogue.find(terms.contractorId());

        DraftOutcome outcome = InterventionDrafting.draft(access, issue,
                interventions.findActiveForIssue(issue.issueId()), terms, contractor, newId, now);
        interventions.add(outcome.intervention(), outcome.proposal());
        Intervention saved = outcome.intervention();
        return new DraftReceipt(saved.id(), saved.state(), saved.version(), saved.currentProposalVersion(),
                saved.contractorId(), false);
    }

    /**
     * The original result, for a caller who still administers the building
     * (technical design 3.3, "Repeated command"). The same key and the same
     * request hash mean the same contractor, so the receipt is rebuilt from the
     * request, whatever happened to the draft since.
     */
    private DraftReceipt replay(CreateInterventionCommand command, IdempotencyRecord record) {
        ProjectedIssue issue = issues.find(command.issueId()).orElseThrow(AccessDeniedException::new);
        InterventionDrafting.authorise(access(command), issue);
        return new DraftReceipt(new InterventionId(record.resourceId()), InterventionState.AWAITING_APPROVAL,
                record.resourceVersion(), 1, InterventionDrafting.parseContractorId(command.contractorId()), true);
    }

    private AdministratorAccess access(CreateInterventionCommand command) {
        return AdministratorAccess.of(command.actor(), memberships.findByUser(command.actor()));
    }
}
