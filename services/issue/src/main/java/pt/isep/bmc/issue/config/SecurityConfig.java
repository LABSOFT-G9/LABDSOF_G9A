package pt.isep.bmc.issue.config;

import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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
import org.springframework.security.web.util.matcher.RequestMatcher;
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
 * <p>Stateless, no session; CSRF protection through a required custom header
 * is pending (technical design 4.1, name open in EN01-U6).
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
    SecurityFilterChain health(HttpSecurity http, Environment environment) throws Exception {
        RequestMatcher healthOnManagementPort = request -> HEALTH_PATHS.contains(request.getRequestURI())
                && Integer.valueOf(request.getLocalPort())
                        .equals(environment.getProperty("local.management.port", Integer.class));
        http.securityMatcher(healthOnManagementPort)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain api(HttpSecurity http, JsonMapper json) throws Exception {
        ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint(json);
        http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()).authenticationEntryPoint(entryPoint))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable());
        return http.build();
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
