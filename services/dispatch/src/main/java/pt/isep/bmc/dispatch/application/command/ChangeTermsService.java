package pt.isep.bmc.dispatch.application.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import pt.isep.bmc.dispatch.application.idempotency.IdempotentExecutor;
import pt.isep.bmc.dispatch.application.port.in.ChangeTermsUseCase;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyRecord;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyScope;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyStore;
import pt.isep.bmc.dispatch.application.port.out.InterventionRepository;
import pt.isep.bmc.dispatch.application.port.out.IssueProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.MembershipProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.TransactionRunner;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionAgreement;
import pt.isep.bmc.dispatch.domain.intervention.InterventionAgreement.ProposalTerms;
import pt.isep.bmc.dispatch.domain.intervention.InterventionAgreement.TermsOutcome;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.intervention.ProposalVersion;
import pt.isep.bmc.dispatch.domain.projection.AdministratorAccess;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;

/**
 * The administrator writes new terms before the contractor's answer (US-07):
 * a new proposal version, {@code awaiting_approval}, and an earlier approval
 * no longer counts. No event is written: the issue stays {@code
 * in_coordination} either way (technical design 3.3), and the contracts have
 * no event for changed terms yet.
 */
public final class ChangeTermsService implements ChangeTermsUseCase {

    public static final String OPERATION = "POST /interventions/{id}/proposals";
    static final Duration KEY_RETENTION = Duration.ofHours(24);
    private static final int OK = 200;

    private final IssueProjectionRepository issues;
    private final MembershipProjectionRepository memberships;
    private final InterventionRepository interventions;
    private final IdempotencyStore idempotency;
    private final IdempotentExecutor idempotent;
    private final Clock clock;

    public ChangeTermsService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            MembershipProjectionRepository memberships,
            InterventionRepository interventions,
            IdempotencyStore idempotency,
            Clock clock) {
        this.issues = issues;
        this.memberships = memberships;
        this.interventions = interventions;
        this.idempotency = idempotency;
        this.idempotent = new IdempotentExecutor(transactions, idempotency);
        this.clock = clock;
    }

    @Override
    public TermsChanged changeTerms(ChangeTermsCommand command) {
        IdempotencyScope scope = new IdempotencyScope(command.actor(), OPERATION, command.idempotencyKey());
        String requestHash = IdempotentExecutor.fingerprint(command.interventionId(), command.expectedVersion(),
                command.scopeExtract(), command.windowStart(), command.windowEnd(), command.terms());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return idempotent.execute(scope, requestHash, now, () -> apply(command, scope, requestHash, now),
                record -> replay(command, record));
    }

    private TermsChanged apply(ChangeTermsCommand command, IdempotencyScope scope, String requestHash, Instant now) {
        idempotency.record(new IdempotencyRecord(scope, requestHash, OK, command.interventionId().value(),
                command.expectedVersion() + 1, now, now.plus(KEY_RETENTION)));

        Intervention unlocked = interventions.find(command.interventionId()).orElseThrow(AccessDeniedException::new);
        AdministratorAccess access = access(command);
        access.requireAdministers(unlocked.buildingId());
        ProposalTerms terms = InterventionAgreement.validateTerms(command.scopeExtract(), command.windowStart(),
                command.windowEnd(), command.terms(), now);

        ProjectedIssue issue = issues.findForUpdate(unlocked.issueId())
                .orElseThrow(() -> new IllegalStateException("intervention points at an issue missing from the projection"));
        Intervention current = interventions.findForUpdate(command.interventionId())
                .orElseThrow(AccessDeniedException::new);
        TermsOutcome outcome = InterventionAgreement.changeTerms(access, current, issue, terms,
                command.expectedVersion(), now);

        Intervention next = outcome.intervention();
        interventions.update(next);
        interventions.addProposal(outcome.proposal());
        return new TermsChanged(next.id(), next.state(), next.version(), outcome.proposal().number(), false);
    }

    /** The original result, for a caller who still administers the building. */
    private TermsChanged replay(ChangeTermsCommand command, IdempotencyRecord record) {
        Intervention intervention = interventions.find(command.interventionId())
                .orElseThrow(() -> new IllegalStateException("idempotency record points at a missing intervention"));
        access(command).requireAdministers(intervention.buildingId());
        ProposalVersion created = interventions.findProposalCreatedAt(intervention.id(), record.resourceVersion())
                .orElseThrow(() -> new IllegalStateException("idempotency record points at a missing proposal"));
        return new TermsChanged(intervention.id(), InterventionState.AWAITING_APPROVAL, record.resourceVersion(),
                created.number(), true);
    }

    private AdministratorAccess access(ChangeTermsCommand command) {
        return AdministratorAccess.of(command.actor(), memberships.findByUser(command.actor()));
    }
}
