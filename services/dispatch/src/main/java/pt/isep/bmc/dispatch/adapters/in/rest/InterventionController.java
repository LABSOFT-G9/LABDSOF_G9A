package pt.isep.bmc.dispatch.adapters.in.rest;

import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.AssignmentListResponse;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.ContractorReplacedResponse;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.CreateInterventionRequest;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.DraftReceiptResponse;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.InterventionListResponse;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.ReplaceContractorRequest;
import pt.isep.bmc.dispatch.application.command.ContractorReplaced;
import pt.isep.bmc.dispatch.application.command.CreateInterventionCommand;
import pt.isep.bmc.dispatch.application.command.DraftReceipt;
import pt.isep.bmc.dispatch.application.command.ReplaceContractorCommand;
import pt.isep.bmc.dispatch.application.error.NotFoundException;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.application.port.in.CreateInterventionUseCase;
import pt.isep.bmc.dispatch.application.port.in.GetInterventionQuery;
import pt.isep.bmc.dispatch.application.port.in.ReplaceContractorUseCase;
import pt.isep.bmc.dispatch.application.query.AssignmentView;
import pt.isep.bmc.dispatch.application.query.InterventionRead;
import pt.isep.bmc.dispatch.application.query.InterventionView;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;

/**
 * Draft intervention requests (US-06, technical design 3.2 and 4.2) and the
 * reads of administrators and the assigned contractor (US-07). Saving a draft
 * sends nothing. Every response carries the intervention's version as
 * the {@code ETag}; a change sends it back as {@code If-Match}.
 */
@RestController
@RequestMapping(path = "/interventions", produces = MediaType.APPLICATION_JSON_VALUE)
class InterventionController {

    private final CreateInterventionUseCase create;
    private final ReplaceContractorUseCase replace;
    private final GetInterventionQuery get;

    InterventionController(CreateInterventionUseCase create, ReplaceContractorUseCase replace, GetInterventionQuery get) {
        this.create = create;
        this.replace = replace;
        this.get = get;
    }

    /** Saves a draft: {@code 201}, state {@code awaiting_approval}, version 1, proposal version 1. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DraftReceiptResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = IdempotencyKey.FIELD, required = false) String idempotencyKey,
            @RequestBody(required = false) CreateInterventionRequest body) {
        IdempotencyKey key = new IdempotencyKey(idempotencyKey);
        CreateInterventionRequest request = body == null ? new CreateInterventionRequest(null, null, null, null, null) : body;
        RequestIds.check(request.issueId(), "issueId").ifPresent(violation -> {
            throw new ValidationException(List.of(violation));
        });
        CreateInterventionRequest.WindowBody window = request.window() == null
                ? new CreateInterventionRequest.WindowBody(null, null)
                : request.window();
        DraftReceipt receipt = create.create(new CreateInterventionCommand(
                Actors.of(jwt),
                key,
                new IssueId(RequestIds.parse(request.issueId()).orElseThrow()),
                request.contractorId(),
                request.scopeExtract(),
                window.start(),
                window.end(),
                request.terms()));
        return ResponseEntity.created(URI.create("/interventions/" + receipt.interventionId()))
                .eTag(Long.toString(receipt.version()))
                .body(new DraftReceiptResponse(receipt.interventionId().toString(), receipt.state().value(),
                        receipt.version(), receipt.proposalVersion(), receipt.contractorId().toString()));
    }

    /**
     * Selects another contractor for a draft. {@code If-Match} is the version
     * the administrator was looking at; a stale one answers {@code 409
     * VERSION_CONFLICT} and changes nothing, so the client offers to reload.
     */
    @PatchMapping(path = "/{interventionId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ContractorReplacedResponse> replaceContractor(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String interventionId,
            @RequestHeader(name = EntityVersions.FIELD, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKey.FIELD, required = false) String idempotencyKey,
            @RequestBody(required = false) ReplaceContractorRequest body) {
        ContractorReplaced result = replace.replace(new ReplaceContractorCommand(
                Actors.of(jwt),
                new IdempotencyKey(idempotencyKey),
                id(interventionId),
                EntityVersions.parseIfMatch(ifMatch),
                body == null ? null : body.contractorId()));
        return ResponseEntity.ok()
                .eTag(Long.toString(result.version()))
                .body(new ContractorReplacedResponse(result.interventionId().toString(), result.state().value(),
                        result.version(), result.contractorId().toString()));
    }

    /**
     * The administrator's view, or the assigned contractor's once the request
     * was sent to them; anyone else gets {@code 404}.
     */
    @GetMapping("/{interventionId}")
    ResponseEntity<Object> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String interventionId) {
        InterventionRead read = get.get(Actors.of(jwt), id(interventionId));
        Object body = switch (read) {
            case InterventionView view -> InterventionResponses.administrator(view);
            case AssignmentView view -> InterventionResponses.contractor(view);
        };
        return ResponseEntity.ok().eTag(Long.toString(read.version())).body(body);
    }

    /**
     * {@code ?issueId=}: the issue's interventions, newest first, for its
     * administrators. {@code ?assigned=me}: the requests sent to the caller as
     * a contractor. Exactly one of the two.
     */
    @GetMapping
    Object list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "issueId", required = false) String issueId,
            @RequestParam(name = "assigned", required = false) String assigned) {
        if (assigned != null) {
            if (issueId != null) {
                throw ValidationException.of("assigned", "not_with_issueId");
            }
            if (!"me".equals(assigned)) {
                throw ValidationException.of("assigned", "invalid");
            }
            return new AssignmentListResponse(get.listAssigned(Actors.of(jwt)).stream()
                    .map(InterventionResponses::contractor)
                    .toList());
        }
        RequestIds.check(issueId, "issueId").ifPresent(violation -> {
            throw new ValidationException(List.of(violation));
        });
        return new InterventionListResponse(get.listForIssue(Actors.of(jwt),
                        new IssueId(RequestIds.parse(issueId).orElseThrow()))
                .stream()
                .map(InterventionResponses::administrator)
                .toList());
    }

    /** An ID that is not a UUID cannot name an intervention: not found, like any unknown one. */
    private static InterventionId id(String raw) {
        return new InterventionId(RequestIds.parse(raw).orElseThrow(NotFoundException::new));
    }
}
