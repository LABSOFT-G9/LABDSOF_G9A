package pt.isep.bmc.dispatch.adapters.in.rest;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.ApproveProposalRequest;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.ChangeTermsRequest;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.CreateInterventionRequest;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.ProposalApprovedResponse;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.RespondRequest;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.ResponseRecordedResponse;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.TermsChangedResponse;
import pt.isep.bmc.dispatch.application.command.ApproveProposalCommand;
import pt.isep.bmc.dispatch.application.command.ChangeTermsCommand;
import pt.isep.bmc.dispatch.application.command.ProposalApproved;
import pt.isep.bmc.dispatch.application.command.RespondCommand;
import pt.isep.bmc.dispatch.application.command.ResponseRecorded;
import pt.isep.bmc.dispatch.application.command.TermsChanged;
import pt.isep.bmc.dispatch.application.error.NotFoundException;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.application.port.in.ApproveProposalUseCase;
import pt.isep.bmc.dispatch.application.port.in.ChangeTermsUseCase;
import pt.isep.bmc.dispatch.application.port.in.RespondToRequestUseCase;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;

/**
 * Agreeing the terms (US-07, technical design 3.2 and 4.2): approval and
 * changed terms by the building's administrator, the answer by the assigned
 * contractor. Each needs {@code If-Match} and an {@code Idempotency-Key}, and
 * answers with the intervention's new version as the {@code ETag}.
 */
@RestController
@RequestMapping(path = "/interventions/{interventionId}", produces = MediaType.APPLICATION_JSON_VALUE)
class InterventionAgreementController {

    private static final CreateInterventionRequest.WindowBody NO_WINDOW =
            new CreateInterventionRequest.WindowBody(null, null);

    private final ApproveProposalUseCase approve;
    private final RespondToRequestUseCase respond;
    private final ChangeTermsUseCase changeTerms;

    InterventionAgreementController(
            ApproveProposalUseCase approve, RespondToRequestUseCase respond, ChangeTermsUseCase changeTerms) {
        this.approve = approve;
        this.respond = respond;
        this.changeTerms = changeTerms;
    }

    /** Approves the named, current version and sends the request: {@code sent}, or {@code scheduling_pending}. */
    @PostMapping(path = "/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ProposalApprovedResponse> approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String interventionId,
            @RequestHeader(name = EntityVersions.FIELD, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKey.FIELD, required = false) String idempotencyKey,
            @RequestBody(required = false) ApproveProposalRequest body,
            HttpServletRequest request) {
        ProposalApproved result = approve.approve(new ApproveProposalCommand(
                Actors.of(jwt),
                new IdempotencyKey(idempotencyKey),
                id(interventionId),
                EntityVersions.parseIfMatch(ifMatch),
                body == null ? null : body.proposalVersion(),
                CorrelationIdFilter.of(request)));
        return ResponseEntity.ok()
                .eTag(Long.toString(result.version()))
                .body(new ProposalApprovedResponse(result.interventionId().toString(), result.state().value(),
                        result.version(), result.approvedProposalVersion()));
    }

    /** The assigned contractor accepts, declines or counterproposes the approved version. */
    @PostMapping(path = "/response", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ResponseRecordedResponse> respond(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String interventionId,
            @RequestHeader(name = EntityVersions.FIELD, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKey.FIELD, required = false) String idempotencyKey,
            @RequestBody(required = false) RespondRequest body,
            HttpServletRequest request) {
        RespondRequest answer = body == null ? new RespondRequest(null, null, null, null, null, null, null) : body;
        CreateInterventionRequest.WindowBody window = answer.window() == null ? NO_WINDOW : answer.window();
        RespondRequest.Cost cost = answer.proposedCost();
        ResponseRecorded result = respond.respond(new RespondCommand(
                Actors.of(jwt),
                new IdempotencyKey(idempotencyKey),
                id(interventionId),
                EntityVersions.parseIfMatch(ifMatch),
                answer.proposalVersion(),
                answer.decision(),
                answer.reasonCode(),
                answer.note(),
                window.start(),
                window.end(),
                answer.terms(),
                cost == null ? null : cost.amount(),
                cost == null ? null : cost.currency(),
                CorrelationIdFilter.of(request)));
        return ResponseEntity.ok()
                .eTag(Long.toString(result.version()))
                .body(new ResponseRecordedResponse(result.interventionId().toString(), result.state().value(),
                        result.version(), result.proposalVersion()));
    }

    /** New terms before the contractor's answer: a new version in {@code awaiting_approval}. */
    @PostMapping(path = "/proposals", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TermsChangedResponse> changeTerms(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String interventionId,
            @RequestHeader(name = EntityVersions.FIELD, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKey.FIELD, required = false) String idempotencyKey,
            @RequestBody(required = false) ChangeTermsRequest body,
            HttpServletRequest request) {
        ChangeTermsRequest terms = body == null ? new ChangeTermsRequest(null, null, null) : body;
        CreateInterventionRequest.WindowBody window = terms.window() == null ? NO_WINDOW : terms.window();
        TermsChanged result = changeTerms.changeTerms(new ChangeTermsCommand(
                Actors.of(jwt),
                new IdempotencyKey(idempotencyKey),
                id(interventionId),
                EntityVersions.parseIfMatch(ifMatch),
                terms.scopeExtract(),
                window.start(),
                window.end(),
                terms.terms(),
                CorrelationIdFilter.of(request)));
        return ResponseEntity.ok()
                .eTag(Long.toString(result.version()))
                .body(new TermsChangedResponse(result.interventionId().toString(), result.state().value(),
                        result.version(), result.proposalVersion()));
    }

    /** An ID that is not a UUID cannot name an intervention: not found, like any unknown one. */
    private static InterventionId id(String raw) {
        return new InterventionId(RequestIds.parse(raw).orElseThrow(NotFoundException::new));
    }
}
