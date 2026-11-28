package pt.isep.bmc.notification.adapters.in.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code 401} as problem+json, never a redirect, so the client can send the
 * user to the edge plugin's login URI (technical design 4.1). Keeps the
 * standard {@code WWW-Authenticate: Bearer} header.
 */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
    private final JsonMapper json;

    public ProblemAuthenticationEntryPoint(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        bearer.commence(request, response, e);
        ProblemDetail problem = Problems.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "A valid access token is required.", CorrelationIdFilter.of(request));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(problem));
    }
}
