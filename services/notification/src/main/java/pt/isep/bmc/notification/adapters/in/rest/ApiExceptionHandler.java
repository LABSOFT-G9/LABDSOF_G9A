package pt.isep.bmc.notification.adapters.in.rest;

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
import pt.isep.bmc.notification.application.error.NotFoundException;
import pt.isep.bmc.notification.application.error.StorageUnavailableException;
import pt.isep.bmc.notification.application.port.out.TransactionConflictException;
import pt.isep.bmc.notification.domain.shared.ValidationException;

/**
 * Maps errors to {@code application/problem+json} (technical design 4.1):
 * {@code 400} validation, {@code 404} unknown or another user's notification,
 * {@code 503} database unavailable. Unexpected errors
 * answer {@code 500} without details; only the exception class is logged.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ProblemDetail> validation(ValidationException e, HttpServletRequest request) {
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, e.code(), e.getMessage(),
                CorrelationIdFilter.of(request));
        List<Map<String, String>> errors = e.violations().stream()
                .map(v -> Map.of("field", v.field(), "reason", v.reason()))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException e, HttpServletRequest request) {
        ProblemDetail problem = Problems.of(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(),
                CorrelationIdFilter.of(request));
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /** The database failed: nothing was changed, try again. */
    @ExceptionHandler({StorageUnavailableException.class, TransactionConflictException.class})
    ResponseEntity<ProblemDetail> storage(RuntimeException e, HttpServletRequest request) {
        ProblemDetail problem = Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                "Notifications are unavailable right now. Please try again.", CorrelationIdFilter.of(request));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unexpected error: {}", e.getClass().getName());
        ProblemDetail problem = Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Unexpected error.", CorrelationIdFilter.of(request));
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /** Spring MVC's own errors (missing header, 405, 406...). */
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
}
