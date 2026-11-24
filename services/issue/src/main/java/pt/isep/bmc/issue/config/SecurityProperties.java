package pt.isep.bmc.issue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token validation settings (ADR-005), from the environment:
 * {@code OIDC_ISSUER_URI}, {@code OIDC_JWK_SET_URI} (optional, defaults to
 * Keycloak's certs endpoint under the issuer) and {@code OIDC_AUDIENCE}; and
 * the custom request header every state-changing request must carry against
 * CSRF ({@code CSRF_HEADER}).
 */
@ConfigurationProperties("bmc.security")
record SecurityProperties(String issuerUri, String jwkSetUri, String audience, String csrfHeader) {

    boolean issuerConfigured() {
        return issuerUri != null && !issuerUri.isBlank();
    }

    String effectiveJwkSetUri() {
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            return jwkSetUri;
        }
        String base = issuerUri.endsWith("/") ? issuerUri.substring(0, issuerUri.length() - 1) : issuerUri;
        return base + "/protocol/openid-connect/certs";
    }

    /** The CSRF header name; refuses to start without one. */
    String requiredCsrfHeader() {
        if (csrfHeader == null || csrfHeader.isBlank()) {
            throw new IllegalStateException("bmc.security.csrf-header (CSRF_HEADER) must not be blank");
        }
        return csrfHeader.strip();
    }
}
