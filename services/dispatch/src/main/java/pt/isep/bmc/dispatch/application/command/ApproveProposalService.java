package pt.isep.bmc.dispatch.application.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import pt.isep.bmc.dispatch.application.idempotency.IdempotentExecutor;
import pt.isep.bmc.dispatch.application.port.in.ApproveProposalUseCase;
import pt.isep.bmc.dispatch.application.port.out.BookingRepository;
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
import pt.isep.bmc.dispatch.domain.event.InterventionApproved;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionAgreement;
import pt.isep.bmc.dispatch.domain.intervention.InterventionAgreement.ApprovalOutcome;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.intervention.ProposalVersion;
import pt.isep.bmc.dispatch.domain.projection.AdministratorAccess;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;

/**
 * Approves a proposal version and sends the request (US-07), with {@code
 * If-Match} and an idempotency key. The intervention, the approval, the key
 * and the {@code InterventionApproved} outbox row are one transaction, so a
 * request is sent once however often the approval is repeated. Approving a
 * contractor's counterproposal agrees the terms, so it also saves the booking
 * request (US-08).
 *
 * <p>Locks the issue projection row before the intervention row, in the same
 * order as every other command and the Issue event consumer.
 */
public final class ApproveProposalService implements ApproveProposalUseCase {

    public static final String OPERATION = "POST /interventions/{id}/approval";
    static final Duration KEY_RETENTION = Duration.ofHours(24);
    private static final int OK = 200;

    private final IssueProjectionRepository issues;
    private final MembershipProjectionRepository memberships;
    private final ContractorCatalogue catalogue;
    private final InterventionRepository interventions;
    private final EventOutbox outbox;
    private final BookingRepository bookings;
    private final IdempotencyStore idempotency;
    private final IdempotentExecutor idempotent;
    private final IdGenerator ids;
    private final Clock clock;

    public ApproveProposalService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            MembershipProjectionRepository memberships,
            ContractorCatalogue catalogue,
            InterventionRepository interventions,
            EventOutbox outbox,
            BookingRepository bookings,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        this.bookings = bookings;
        this.issues = issues;
        this.memberships = memberships;
        this.catalogue = catalogue;
        this.interventions = interventions;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.idempotent = new IdempotentExecutor(transactions, idempotency);
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public ProposalApproved approve(ApproveProposalCommand command) {
        IdempotencyScope scope = new IdempotencyScope(command.actor(), OPERATION, command.idempotencyKey());
        String requestHash = IdempotentExecutor.fingerprint(
                command.interventionId(), command.expectedVersion(), command.proposalVersion());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return idempotent.execute(scope, requestHash, now, () -> apply(command, scope, requestHash, now),
                record -> replay(command, record));
    }

    private ProposalApproved apply(ApproveProposalCommand command, IdempotencyScope scope, String requestHash,
            Instant now) {
        idempotency.record(new IdempotencyRecord(scope, requestHash, OK, command.interventionId().value(),
                command.expectedVersion() + 1, now, now.plus(KEY_RETENTION)));

        Intervention unlocked = interventions.find(command.interventionId()).orElseThrow(AccessDeniedException::new);
        AdministratorAccess access = access(command);
        access.requireAdministers(unlocked.buildingId());
        int proposalVersion = InterventionAgreement.validateProposalVersion(command.proposalVersion());

        ProjectedIssue issue = issues.findForUpdate(unlocked.issueId())
                .orElseThrow(() -> new IllegalStateException("intervention points at an issue missing from the projection"));
        Intervention current = interventions.findForUpdate(command.interventionId())
                .orElseThrow(AccessDeniedException::new);
        ProposalVersion proposal = interventions.findProposalForUpdate(current.id(), current.currentProposalVersion())
                .orElseThrow(() -> new IllegalStateException("the current proposal version is missing"));
        ApprovalOutcome outcome = InterventionAgreement.approve(access, current, issue, proposal,
                catalogue.find(current.contractorId()), command.expectedVersion(), proposalVersion, now);

        Intervention approved = outcome.intervention();
        interventions.update(approved);
        interventions.recordApproval(outcome.approvedProposal());
        outbox.append(new OutboxEvent(ids.newId(), new InterventionApproved(approved.id().value(), approved.version(),
                approved.buildingId(), now, approved.issueId(), outcome.contractorUserId(),
                outcome.contractorDisplayName(), proposalVersion, approved.state()), command.correlationId()));
        AgreedBookings.requestIfAgreed(approved, proposal.window(), issue, catalogue, bookings, outbox, ids,
                command.correlationId(), now);
        return new ProposalApproved(approved.id(), approved.state(), approved.version(), proposalVersion, false);
    }

    /** The original result, for a caller who still administers the building. */
    private ProposalApproved replay(ApproveProposalCommand command, IdempotencyRecord record) {
        Intervention intervention = interventions.find(command.interventionId())
                .orElseThrow(() -> new IllegalStateException("idempotency record points at a missing intervention"));
        access(command).requireAdministers(intervention.buildingId());
        int proposalVersion = command.proposalVersion();
        ProposalVersion approved = interventions.findProposal(intervention.id(), proposalVersion)
                .orElseThrow(() -> new IllegalStateException("idempotency record points at a missing proposal"));
        InterventionState state = approved.byContractor() ? InterventionState.SCHEDULING_PENDING : InterventionState.SENT;
        return new ProposalApproved(intervention.id(), state, record.resourceVersion(), proposalVersion, true);
    }

    private AdministratorAccess access(ApproveProposalCommand command) {
        return AdministratorAccess.of(command.actor(), memberships.findByUser(command.actor()));
    }
}
