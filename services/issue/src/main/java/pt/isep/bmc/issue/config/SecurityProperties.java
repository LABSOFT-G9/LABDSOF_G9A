package pt.isep.bmc.issue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token validation settings (ADR-005), from the environment:
 * {@code OIDC_ISSUER_URI}, {@code OIDC_JWK_SET_URI} (optional, defaults to
 * Keycloak's certs endpoint under the issuer) and {@code OIDC_AUDIENCE}.
 */
@ConfigurationProperties("bmc.security")
record SecurityProperties(String issuerUri, String jwkSetUri, String audience) {

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
}
