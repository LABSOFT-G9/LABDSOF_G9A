package pt.isep.bmc.dispatch.adapters.in.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import pt.isep.bmc.dispatch.application.error.IdempotencyKeyReusedException;
import pt.isep.bmc.dispatch.application.error.NotFoundException;
import pt.isep.bmc.dispatch.application.error.StorageUnavailableException;
import pt.isep.bmc.dispatch.application.port.out.TransactionConflictException;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.BusinessRuleException;
import pt.isep.bmc.dispatch.domain.shared.DomainException;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;
import pt.isep.bmc.dispatch.domain.shared.VersionConflictException;

/**
 * Maps errors to {@code application/problem+json} (technical design 4.1):
 * {@code 400} validation, {@code 404} unknown or outside scope, {@code 409}
 * stale version or invalid transition, {@code 422} idempotency key reused or a business rule
 * ({@code CONTRACTOR_HAS_NO_ACCOUNT}, {@code CONTRACTOR_NOT_ELIGIBLE}, {@code CONTRACTOR_ALREADY_CONTACTED}),
 * {@code 503} database unavailable. Unexpected errors
 * answer {@code 500} without details; only the exception class is logged.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ProblemDetail> validation(ValidationException e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, e, request);
        List<Map<String, String>> errors = e.violations().stream()
                .map(v -> Map.of("field", v.field(), "reason", v.reason()))
                .toList();
        problem.setProperty("errors", errors);
        return respond(problem);
    }

    @ExceptionHandler({AccessDeniedException.class, NotFoundException.class})
    ResponseEntity<ProblemDetail> notFound(RuntimeException e, HttpServletRequest request) {
        return respond(Problems.of(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "The resource does not exist or is outside your scope.", CorrelationIdFilter.of(request)));
    }

    /** Stale {@code If-Match}: tells the client what the resource is now, and changes nothing. */
    @ExceptionHandler(VersionConflictException.class)
    ResponseEntity<ProblemDetail> versionConflict(VersionConflictException e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, e, request);
        problem.setProperty("currentVersion", e.currentVersion());
        problem.setProperty("currentState", e.currentState());
        return respond(problem);
    }

    /**
     * Not allowed in the current state. For an issue that already has an
     * active intervention, {@code activeInterventionId} names it, so the
     * administrator can open it instead.
     */
    @ExceptionHandler(InvalidTransitionException.class)
    ResponseEntity<ProblemDetail> invalidTransition(InvalidTransitionException e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, e, request);
        problem.setProperty("currentState", e.currentState());
        e.activeInterventionId().ifPresent(id -> problem.setProperty("activeInterventionId", id.toString()));
        return respond(problem);
    }

    /** A business rule refused a well-formed request ({@code 422}, technical design 4.1 "semantic rule"). */
    @ExceptionHandler(BusinessRuleException.class)
    ResponseEntity<ProblemDetail> businessRule(BusinessRuleException e, HttpServletRequest request) {
        return respond(problem(HttpStatus.UNPROCESSABLE_CONTENT, e, request));
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ProblemDetail> keyReused(IdempotencyKeyReusedException e, HttpServletRequest request) {
        return respond(Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED",
                e.getMessage(), CorrelationIdFilter.of(request)));
    }

    /** The database failed, or rolled the request back for a concurrent one: nothing was changed, try again. */
    @ExceptionHandler({StorageUnavailableException.class, TransactionConflictException.class})
    ResponseEntity<ProblemDetail> storage(RuntimeException e, HttpServletRequest request) {
        ProblemDetail problem = Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                "The request could not be completed. Nothing was changed; please try again.",
                CorrelationIdFilter.of(request));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unexpected error: {}", e.getClass().getName());
        return respond(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Unexpected error.", CorrelationIdFilter.of(request)));
    }

    /** Spring MVC's own errors (malformed JSON, missing header, 405, 415...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String correlationId = request instanceof ServletWebRequest servlet
                ? CorrelationIdFilter.of(servlet.getRequest())
                : "unknown";
        // Generic detail: Spring's messages can quote request content.
        ProblemDetail problem = Problems.of(status, Problems.codeFor(status), "The request was rejected.", correlationId);
        return super.handleExceptionInternal(ex, problem, headers, status, request);
    }

    static ProblemDetail problem(HttpStatus status, DomainException e, HttpServletRequest request) {
        return Problems.of(status, e.code(), e.getMessage(), CorrelationIdFilter.of(request));
    }

    static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
