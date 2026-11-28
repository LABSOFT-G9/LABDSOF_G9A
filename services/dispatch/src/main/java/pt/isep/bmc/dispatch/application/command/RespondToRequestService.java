package pt.isep.bmc.dispatch.application.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import pt.isep.bmc.dispatch.application.idempotency.IdempotentExecutor;
import pt.isep.bmc.dispatch.application.port.in.RespondToRequestUseCase;
import pt.isep.bmc.dispatch.application.port.out.BookingRepository;
import pt.isep.bmc.dispatch.application.port.out.ContractorCatalogue;
import pt.isep.bmc.dispatch.application.port.out.EventOutbox;
import pt.isep.bmc.dispatch.application.port.out.IdGenerator;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyRecord;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyScope;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyStore;
import pt.isep.bmc.dispatch.application.port.out.InterventionRepository;
import pt.isep.bmc.dispatch.application.port.out.IssueProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.OutboxEvent;
import pt.isep.bmc.dispatch.application.port.out.TransactionRunner;
import pt.isep.bmc.dispatch.domain.event.ContractorResponded;
import pt.isep.bmc.dispatch.domain.event.DomainEvent;
import pt.isep.bmc.dispatch.domain.event.InterventionDeclined;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionAgreement;
import pt.isep.bmc.dispatch.domain.intervention.InterventionAgreement.ResponseOutcome;
import pt.isep.bmc.dispatch.domain.intervention.InterventionAgreement.ResponseRequest;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.intervention.ProposalVersion;
import pt.isep.bmc.dispatch.domain.intervention.ResponseDecision;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;

/**
 * Records the assigned contractor's answer to the approved version (US-07),
 * with {@code If-Match} and an idempotency key. The intervention, the
 * response, a counterproposal's new version, the key and the outbox row are
 * one transaction: {@code ContractorResponded} for an acceptance or a
 * counterproposal, {@code InterventionDeclined} for a decline. An acceptance
 * also saves the booking request for the agreed window (US-08).
 *
 * <p>Only the contractor whose login is the catalogue entry's, after the
 * request was sent, gets past {@code 404}: Dispatch authorises this from its
 * own data (annex A 1.4).
 */
public final class RespondToRequestService implements RespondToRequestUseCase {

    public static final String OPERATION = "POST /interventions/{id}/response";
    static final Duration KEY_RETENTION = Duration.ofHours(24);
    private static final int OK = 200;

    private final IssueProjectionRepository issues;
    private final ContractorCatalogue catalogue;
    private final InterventionRepository interventions;
    private final EventOutbox outbox;
    private final BookingRepository bookings;
    private final IdempotencyStore idempotency;
    private final IdempotentExecutor idempotent;
    private final IdGenerator ids;
    private final Clock clock;

    public RespondToRequestService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            ContractorCatalogue catalogue,
            InterventionRepository interventions,
            EventOutbox outbox,
            BookingRepository bookings,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        this.bookings = bookings;
        this.issues = issues;
        this.catalogue = catalogue;
        this.interventions = interventions;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.idempotent = new IdempotentExecutor(transactions, idempotency);
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public ResponseRecorded respond(RespondCommand command) {
        IdempotencyScope scope = new IdempotencyScope(command.actor(), OPERATION, command.idempotencyKey());
        String requestHash = IdempotentExecutor.fingerprint(command.interventionId(), command.expectedVersion(),
                command.proposalVersion(), command.decision(), command.reasonCode(), command.note(),
                command.windowStart(), command.windowEnd(), command.terms(), command.costAmount(),
                command.costCurrency());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return idempotent.execute(scope, requestHash, now, () -> apply(command, scope, requestHash, now),
                record -> replay(command, record));
    }

    private ResponseRecorded apply(RespondCommand command, IdempotencyScope scope, String requestHash, Instant now) {
        idempotency.record(new IdempotencyRecord(scope, requestHash, OK, command.interventionId().value(),
                command.expectedVersion() + 1, now, now.plus(KEY_RETENTION)));

        Intervention unlocked = interventions.find(command.interventionId()).orElseThrow(AccessDeniedException::new);
        InterventionAgreement.requireAssignedContractor(command.actor(), unlocked,
                catalogue.find(unlocked.contractorId()));
        ResponseRequest request = InterventionAgreement.validateResponse(command.proposalVersion(),
                command.decision(), command.reasonCode(), command.note(), command.windowStart(), command.windowEnd(),
                command.terms(), command.costAmount(), command.costCurrency(), now);

        ProjectedIssue issue = issues.findForUpdate(unlocked.issueId())
                .orElseThrow(() -> new IllegalStateException("intervention points at an issue missing from the projection"));
        Intervention current = interventions.findForUpdate(command.interventionId())
                .orElseThrow(AccessDeniedException::new);
        int answerable = current.approvedProposalVersion().orElse(current.currentProposalVersion());
        ProposalVersion answered = interventions.findProposalForUpdate(current.id(), answerable)
                .orElseThrow(() -> new IllegalStateException("the proposal version to answer is missing"));
        ResponseOutcome outcome = InterventionAgreement.respond(command.actor(), current, issue, answered, request,
                command.expectedVersion(), now);

        Intervention next = outcome.intervention();
        interventions.update(next);
        outcome.counterproposal().ifPresent(interventions::addProposal);
        interventions.addResponse(outcome.response());
        outbox.append(new OutboxEvent(ids.newId(), event(next, request, now), command.correlationId()));
        AgreedBookings.requestIfAgreed(next, answered.window(), issue, catalogue, bookings, outbox, ids,
                command.correlationId(), now);
        return new ResponseRecorded(next.id(), next.state(), next.version(), next.currentProposalVersion(), false);
    }

    private static DomainEvent event(Intervention next, ResponseRequest request, Instant now) {
        if (request.decision() == ResponseDecision.DECLINE) {
            return new InterventionDeclined(next.id().value(), next.version(), next.buildingId(), now, next.issueId(),
                    request.reason().orElseThrow(), next.state());
        }
        return new ContractorResponded(next.id().value(), next.version(), next.buildingId(), now, next.issueId(),
                request.decision(), request.proposalVersion(), next.state());
    }

    /**
     * The original result, for the contractor who is still assigned. The same
     * key and request hash mean the same decision, so the result is rebuilt
     * from the request; a counterproposal's version is the one created at
     * the recorded intervention version.
     */
    private ResponseRecorded replay(RespondCommand command, IdempotencyRecord record) {
        Intervention intervention = interventions.find(command.interventionId())
                .orElseThrow(() -> new IllegalStateException("idempotency record points at a missing intervention"));
        InterventionAgreement.requireAssignedContractor(command.actor(), intervention,
                catalogue.find(intervention.contractorId()));
        int answered = command.proposalVersion();
        ResponseDecision decision = ResponseDecision.fromValue(command.decision()).orElseThrow();
        return switch (decision) {
            case ACCEPT -> new ResponseRecorded(intervention.id(), InterventionState.SCHEDULING_PENDING,
                    record.resourceVersion(), answered, true);
            case DECLINE -> new ResponseRecorded(intervention.id(), InterventionState.DECLINED,
                    record.resourceVersion(), answered, true);
            // The version the counterproposal created, looked up, never derived from the one answered.
            case COUNTERPROPOSE -> new ResponseRecorded(intervention.id(), InterventionState.AWAITING_APPROVAL,
                    record.resourceVersion(), interventions.findProposalCreatedAt(intervention.id(),
                                    record.resourceVersion())
                            .orElseThrow(() -> new IllegalStateException("idempotency record points at a missing proposal"))
                            .number(), true);
        };
    }
}
