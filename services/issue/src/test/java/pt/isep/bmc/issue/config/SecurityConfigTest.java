package pt.isep.bmc.issue.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class SecurityConfigTest {

    @Test
    void withoutAnIssuerEveryTokenIsRejected() {
        JwtDecoder decoder = new SecurityConfig().jwtDecoder(new SecurityProperties("", "", "bmc-api"));
        assertThatThrownBy(() -> decoder.decode("anything")).isInstanceOf(BadJwtException.class);
    }

    @Test
    void blankAudienceStopsTheStart() {
        assertThatThrownBy(() -> new SecurityConfig().jwtDecoder(new SecurityProperties("http://kc/realms/bmc", "", " ")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void jwkSetUriDefaultsToKeycloakCertsEndpoint() {
        assertThat(new SecurityProperties("http://kc/realms/bmc/", null, "bmc-api").effectiveJwkSetUri())
                .isEqualTo("http://kc/realms/bmc/protocol/openid-connect/certs");
    }
}
