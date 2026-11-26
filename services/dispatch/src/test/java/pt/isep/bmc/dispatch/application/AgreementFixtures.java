package pt.isep.bmc.dispatch.application;

import static pt.isep.bmc.dispatch.application.ApplicationFixtures.*;

import java.time.Duration;
import pt.isep.bmc.dispatch.application.InMemoryPorts.MutableClock;
import pt.isep.bmc.dispatch.application.command.ApproveProposalCommand;
import pt.isep.bmc.dispatch.application.command.ApproveProposalService;
import pt.isep.bmc.dispatch.application.command.ChangeTermsCommand;
import pt.isep.bmc.dispatch.application.command.ChangeTermsService;
import pt.isep.bmc.dispatch.application.command.CreateInterventionCommand;
import pt.isep.bmc.dispatch.application.command.CreateInterventionService;
import pt.isep.bmc.dispatch.application.command.RespondCommand;
import pt.isep.bmc.dispatch.application.command.RespondToRequestService;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.domain.contractor.Contractor;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * The US-07 use cases over one set of in-memory ports, with a draft for a
 * triaged plumbing issue in Building A, and commands built from it.
 */
final class AgreementFixtures {

    final InMemoryPorts ports = ports();
    final MutableClock clock = new MutableClock(NOW);
    final IssueId issueId = newIssueId();
    final ApproveProposalService approval;
    final RespondToRequestService responses;
    final ChangeTermsService terms;
    InterventionId interventionId;

    AgreementFixtures(Contractor contractor) {
        ports.issues.put(issueId, triagedPlumbing(issueId));
        interventionId = new CreateInterventionService(ports, ports, ports, ports, ports, ports, ports, clock)
                .create(new CreateInterventionCommand(ADMIN_A, new IdempotencyKey("draft"), issueId,
                        contractor.id().toString(), SCOPE, WINDOW_START, WINDOW_END, TERMS))
                .interventionId();
        ports.keys.clear();
        clock.advance(Duration.ofMinutes(1));
        approval = new ApproveProposalService(ports, ports, ports, ports, ports, ports, ports, ports, ports, clock);
        responses = new RespondToRequestService(ports, ports, ports, ports, ports, ports, ports, ports, clock);
        terms = new ChangeTermsService(ports, ports, ports, ports, ports, clock);
    }

    AgreementFixtures() {
        this(PLUMBER);
    }

    Intervention stored() {
        return ports.interventions.get(interventionId);
    }

    ApproveProposalCommand approve(UserId actor, String key, long expectedVersion, Integer proposalVersion) {
        return new ApproveProposalCommand(actor, new IdempotencyKey(key), interventionId, expectedVersion,
                proposalVersion, "c-approve");
    }

    RespondCommand accept(String key, long expectedVersion, int proposalVersion) {
        return answer(CONTRACTOR_LOGIN, key, expectedVersion, proposalVersion, "accept", null, null);
    }

    RespondCommand decline(String key, long expectedVersion, int proposalVersion, String reason, String note) {
        return answer(CONTRACTOR_LOGIN, key, expectedVersion, proposalVersion, "decline", reason, note);
    }

    RespondCommand counterpropose(String key, long expectedVersion, int proposalVersion) {
        return new RespondCommand(CONTRACTOR_LOGIN, new IdempotencyKey(key), interventionId, expectedVersion,
                proposalVersion, "counterpropose", null, null, "2026-11-29T09:00:00Z", "2026-11-29T11:00:00Z",
                "Saturday morning; parts extra.", "120.00", "EUR", "c-counter");
    }

    RespondCommand answer(UserId actor, String key, long expectedVersion, int proposalVersion, String decision,
            String reason, String note) {
        return new RespondCommand(actor, new IdempotencyKey(key), interventionId, expectedVersion, proposalVersion,
                decision, reason, note, null, null, null, null, null, "c-respond");
    }

    ChangeTermsCommand changeTerms(String key, long expectedVersion) {
        return new ChangeTermsCommand(ADMIN_A, new IdempotencyKey(key), interventionId, expectedVersion,
                "Leak above garage light fitting, level -1. Bring a ladder.", WINDOW_START, WINDOW_END,
                "Inspect, repair and report the cost.");
    }

    /** Approves version 1: the request is {@code sent} at version 2. */
    void sent() {
        approval.approve(approve(ADMIN_A, "approve-1", 1, 1));
    }
}
