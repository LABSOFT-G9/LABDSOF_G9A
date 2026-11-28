package pt.isep.bmc.notification.adapters.in.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code 403} as problem+json. In this service it is only reached when a
 * state-changing request lacks the custom CSRF header (ADR-005); resources
 * outside the caller's scope answer {@code 404} from the use cases instead.
 */
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonMapper json;
    private final String csrfHeader;

    public ProblemAccessDeniedHandler(JsonMapper json, String csrfHeader) {
        this.json = json;
        this.csrfHeader = csrfHeader;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        ProblemDetail problem = e instanceof CsrfException
                ? Problems.of(HttpStatus.FORBIDDEN, "CSRF_HEADER_REQUIRED",
                        "State-changing requests must carry the " + csrfHeader + " header.", CorrelationIdFilter.of(request))
                : Problems.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "The request is not allowed.", CorrelationIdFilter.of(request));
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(problem));
    }
}
