package pt.isep.bmc.dispatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.support.IntegrationTestSupport;

/**
 * Every API request needs a valid token (ADR-005), errors are problem+json,
 * and a state-changing request without the custom CSRF header is refused
 * before it reaches a controller.
 */
class SecurityIT extends IntegrationTestSupport {

    private static final String SUB = "0199f2a0-0000-7000-8000-000000000201";

    @Test
    void withoutATokenTheApiAnswers401AsAProblem() {
        var response = api.get("/contractors", null);
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.header("Content-Type")).startsWith("application/problem+json");
        assertThat(response.header("WWW-Authenticate")).startsWith("Bearer");
        assertThat(response.json()).containsEntry("code", "UNAUTHENTICATED");
        assertThat(response.header("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void forgedUnsignedExpiredOrForeignTokensAreRejected() {
        assertThat(api.get("/contractors", tokens.forged(SUB)).status()).isEqualTo(401);
        assertThat(api.get("/contractors", tokens.unsigned(SUB)).status()).isEqualTo(401);
        assertThat(api.get("/contractors", tokens.token(SUB, claims -> claims
                .expirationTime(java.util.Date.from(java.time.Instant.now().minusSeconds(120))))).status())
                .isEqualTo(401);
        assertThat(api.get("/contractors", tokens.token(SUB, claims -> claims.audience("another-api"))).status())
                .isEqualTo(401);
        assertThat(api.get("/contractors", tokens.token(SUB, claims -> claims.issuer("http://elsewhere/realms/bmc")))
                .status()).isEqualTo(401);
    }

    @Test
    void aStateChangingRequestWithoutTheCsrfHeaderIsRefused() {
        var response = api.withoutCsrfHeader().post("/interventions", tokens.token(SUB), "key-1", "{}");
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json()).containsEntry("code", "CSRF_HEADER_REQUIRED");
    }
}
