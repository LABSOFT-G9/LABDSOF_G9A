package pt.isep.bmc.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class SecurityConfigTest {

    @Test
    void withoutAnIssuerEveryTokenIsRejected() {
        JwtDecoder decoder = new SecurityConfig().jwtDecoder(new SecurityProperties("", "", "bmc-api", "X-BMC-CSRF"));
        assertThatThrownBy(() -> decoder.decode("anything")).isInstanceOf(BadJwtException.class);
    }

    @Test
    void blankAudienceStopsTheStart() {
        assertThatThrownBy(() -> new SecurityConfig().jwtDecoder(new SecurityProperties("http://kc/realms/bmc", "", " ", "X-BMC-CSRF")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void jwkSetUriDefaultsToKeycloakCertsEndpoint() {
        assertThat(new SecurityProperties("http://kc/realms/bmc/", null, "bmc-api", "X-BMC-CSRF").effectiveJwkSetUri())
                .isEqualTo("http://kc/realms/bmc/protocol/openid-connect/certs");
    }

    @Test
    void blankCsrfHeaderStopsTheStart() {
        assertThatThrownBy(() -> new SecurityProperties("", "", "bmc-api", " ").requiredCsrfHeader())
                .isInstanceOf(IllegalStateException.class);
    }
}
