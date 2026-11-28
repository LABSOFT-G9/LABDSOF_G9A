package pt.isep.bmc.notification.adapters.in.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts {@code X-Correlation-Id} from the edge or generates one, returns it
 * on every response (including {@code 401}s from the security filters), puts
 * it in the logging MDC and makes it available to controllers (technical
 * design 4.1). Values that are not short safe tokens are replaced, so a header
 * cannot inject text into logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = CorrelationIdFilter.class.getName();
    private static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String received = request.getHeader(HEADER);
        String correlationId = received != null && SAFE.matcher(received).matches()
                ? received
                : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** The request's correlation ID; generated if the filter did not run. */
    public static String of(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String id ? id : UUID.randomUUID().toString();
    }
}
