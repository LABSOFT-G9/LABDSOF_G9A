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
import pt.isep.bmc.dispatch.adapters.in.rest.dto.CompletionRequest;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.ExecutionResponse;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.ReworkRequest;
import pt.isep.bmc.dispatch.application.command.CompletionCommand;
import pt.isep.bmc.dispatch.application.command.ExecutionResult;
import pt.isep.bmc.dispatch.application.command.ReworkCommand;
import pt.isep.bmc.dispatch.application.command.StartCommand;
import pt.isep.bmc.dispatch.application.error.NotFoundException;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.application.port.in.ExecutionUseCases;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;

/**
 * Doing the work (US-09, technical design 3.2 and 4.2): start and
 * completion by the assigned contractor (completion also by an
 * administrator with a reason), rework by the administrator. Each needs
 * {@code If-Match} and an {@code Idempotency-Key}.
 */
@RestController
@RequestMapping(path = "/interventions/{interventionId}", produces = MediaType.APPLICATION_JSON_VALUE)
class ExecutionController {

    private final ExecutionUseCases execution;

    ExecutionController(ExecutionUseCases execution) {
        this.execution = execution;
    }

    @PostMapping("/start")
    ResponseEntity<ExecutionResponse> start(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String interventionId,
            @RequestHeader(name = EntityVersions.FIELD, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKey.FIELD, required = false) String idempotencyKey,
            HttpServletRequest request) {
        return answer(execution.start(new StartCommand(Actors.of(jwt), new IdempotencyKey(idempotencyKey),
                id(interventionId), EntityVersions.parseIfMatch(ifMatch), CorrelationIdFilter.of(request))));
    }

    @PostMapping(path = "/completion", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExecutionResponse> completion(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String interventionId,
            @RequestHeader(name = EntityVersions.FIELD, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKey.FIELD, required = false) String idempotencyKey,
            @RequestBody(required = false) CompletionRequest body,
            HttpServletRequest request) {
        CompletionRequest report = body == null ? new CompletionRequest(null, null, null) : body;
        return answer(execution.reportCompletion(new CompletionCommand(Actors.of(jwt), new IdempotencyKey(idempotencyKey),
                id(interventionId), EntityVersions.parseIfMatch(ifMatch), report.summary(), report.performedAt(),
                report.reason(), CorrelationIdFilter.of(request))));
    }

    @PostMapping(path = "/rework", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExecutionResponse> rework(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String interventionId,
            @RequestHeader(name = EntityVersions.FIELD, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKey.FIELD, required = false) String idempotencyKey,
            @RequestBody(required = false) ReworkRequest body,
            HttpServletRequest request) {
        return answer(execution.requestRework(new ReworkCommand(Actors.of(jwt), new IdempotencyKey(idempotencyKey),
                id(interventionId), EntityVersions.parseIfMatch(ifMatch), body == null ? null : body.reason(),
                CorrelationIdFilter.of(request))));
    }

    private static ResponseEntity<ExecutionResponse> answer(ExecutionResult result) {
        return ResponseEntity.ok()
                .eTag(Long.toString(result.version()))
                .body(new ExecutionResponse(result.interventionId().toString(), result.state().value(),
                        result.version()));
    }

    private static InterventionId id(String raw) {
        return new InterventionId(RequestIds.parse(raw).orElseThrow(NotFoundException::new));
    }
}
