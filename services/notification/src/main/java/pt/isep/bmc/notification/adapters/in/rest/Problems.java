package pt.isep.bmc.notification.adapters.in.rest;

import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * RFC 9457 problem details with the extension members {@code code} and
 * {@code correlationId} (technical design 4.1). Details never include
 * user-supplied text.
 */
public final class Problems {

    private static final String TYPE_BASE = "https://bmc.example/problems/";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String detail, String correlationId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", correlationId);
        return problem;
    }

    /** A stable code for a status that has no project-specific one. */
    public static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "VALIDATION_FAILED";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 503 -> "DEPENDENCY_UNAVAILABLE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_REJECTED";
        };
    }
}
