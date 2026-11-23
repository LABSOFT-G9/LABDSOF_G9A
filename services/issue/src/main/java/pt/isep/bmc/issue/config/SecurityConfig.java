package pt.isep.bmc.issue.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.util.matcher.RequestMatcher;
import pt.isep.bmc.issue.adapters.in.rest.CsrfHeaderFilter;
import pt.isep.bmc.issue.adapters.in.rest.ProblemAccessDeniedHandler;
import pt.isep.bmc.issue.adapters.in.rest.ProblemAuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issue is an OAuth2 resource server (ADR-005): every API request needs a
 * valid access token (signature with Keycloak's keys, issuer, audience,
 * expiry, non-blank {@code sub}); then each operation checks memberships in
 * Issue's own database. The edge is not the security control.
 *
 * <p>Fail closed: without {@code OIDC_ISSUER_URI} the service still starts
 * (so the dev host keeps running until EN-02-04 adds Keycloak) but rejects
 * every token, so every API call answers {@code 401}. A blank audience stops
 * the start.
 *
 * <p>Stateless, no session. Every state-changing request must carry the
 * custom CSRF header ({@code CSRF_HEADER}, ADR-005, see
 * {@link CsrfHeaderFilter}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    static final Set<String> HEALTH_PATHS = Set.of("/healthz", "/readyz");

    /**
     * {@code /healthz} and {@code /readyz} without a token, and only on the
     * internal management port (never routed by Traefik). Every other request
     * falls through to the API chain below.
     */
    @Bean
    @Order(1)
    SecurityFilterChain health(HttpSecurity http, Environment environment, SecurityProperties properties)
            throws Exception {
        RequestMatcher healthOnManagementPort = request -> HEALTH_PATHS.contains(request.getRequestURI())
                && Integer.valueOf(request.getLocalPort())
                        .equals(environment.getProperty("local.management.port", Integer.class));
        http.securityMatcher(healthOnManagementPort)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(customHeaderCsrf(new CsrfHeaderFilter(properties.requiredCsrfHeader(), new AccessDeniedHandlerImpl())))
                .requestCache(cache -> cache.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain api(HttpSecurity http, JsonMapper json, SecurityProperties properties) throws Exception {
        String csrfHeader = properties.requiredCsrfHeader();
        ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint(json);
        ProblemAccessDeniedHandler denied = new ProblemAccessDeniedHandler(json, csrfHeader);
        CsrfHeaderFilter csrfHeaderFilter = new CsrfHeaderFilter(csrfHeader, denied);
        http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()).authenticationEntryPoint(entryPoint))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(customHeaderCsrf(csrfHeaderFilter))
                // Spring exempts bearer-token requests from its CSRF filter; this
                // filter applies the same rule to them (all API calls carry one).
                .addFilterAfter(csrfHeaderFilter, CsrfFilter.class)
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable());
        return http.build();
    }

    /**
     * Spring's CSRF filter stays on with the same rule as
     * {@link CsrfHeaderFilter}: it rejects a state-changing request without
     * the custom header. No synchronizer token is ever issued or stored (so no
     * session is created); the header is the defence. Spring applies this
     * only to requests without a bearer token.
     */
    static Customizer<CsrfConfigurer<HttpSecurity>> customHeaderCsrf(CsrfHeaderFilter rule) {
        return csrf -> csrf
                .csrfTokenRepository(new NoStoredCsrfTokens())
                .requireCsrfProtectionMatcher(rule::lacksHeader);
    }

    /** Never issues or stores a token: the custom header is the defence. */
    static final class NoStoredCsrfTokens implements CsrfTokenRepository {

        @Override
        public CsrfToken generateToken(HttpServletRequest request) {
            return new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", UUID.randomUUID().toString());
        }

        @Override
        public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
            // Nothing to store: the service is stateless.
        }

        @Override
        public CsrfToken loadToken(HttpServletRequest request) {
            return null;
        }
    }

    @Bean
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        if (properties.audience() == null || properties.audience().isBlank()) {
            throw new IllegalStateException("bmc.security.audience (OIDC_AUDIENCE) must not be blank");
        }
        if (!properties.issuerConfigured()) {
            log.warn("OIDC_ISSUER_URI is not set: every API request will be rejected with 401");
            return token -> {
                throw new BadJwtException("No token issuer is configured");
            };
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.effectiveJwkSetUri()).build();
        decoder.setJwtValidator(validator(properties.issuerUri(), properties.audience()));
        return decoder;
    }

    /** Issuer, expiry and not-before, audience, and a non-blank subject. */
    static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(List.of(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank()
                        && sub.length() <= 255)));
    }
}
