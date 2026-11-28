package pt.isep.bmc.notification.adapters.in.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF defence of ADR-005: every state-changing request must carry a custom
 * header with a non-blank value. The edge holds the session cookie and turns
 * it into the bearer token, so a cross-site request would otherwise arrive
 * here authenticated. A cross-origin page cannot add a custom header without a
 * CORS pre-flight, and there is no CORS.
 *
 * <p>Needed on top of Spring's CSRF filter because Spring's resource server
 * exempts every request with a bearer token from that filter.
 */
public final class CsrfHeaderFilter extends OncePerRequestFilter {

    /** Methods that never change state (RFC 9110 9.2.1), so need no header. */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final String header;
    private final AccessDeniedHandler denied;

    public CsrfHeaderFilter(String header, AccessDeniedHandler denied) {
        this.header = header;
        this.denied = denied;
    }

    /** True for a state-changing request without the header. */
    public boolean lacksHeader(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return false;
        }
        String value = request.getHeader(header);
        return value == null || value.isBlank();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (lacksHeader(request)) {
            denied.handle(request, response, new MissingCsrfTokenException(null));
            return;
        }
        chain.doFilter(request, response);
    }
}
