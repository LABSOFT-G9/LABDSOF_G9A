package pt.isep.bmc.dispatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.support.Api;
import pt.isep.bmc.dispatch.support.IntegrationTestSupport;

/** Health only on the internal management port, which Traefik never routes. */
class HealthIT extends IntegrationTestSupport {

    @Test
    void livenessAndReadinessOnTheManagementPortOnly() {
        Api management = new Api(environment.getRequiredProperty("local.management.port", Integer.class));
        assertThat(management.get("/healthz", null).status()).isEqualTo(200);
        assertThat(management.get("/readyz", null).status()).isEqualTo(200);
        assertThat(management.get("/healthz", null).body()).doesNotContain("mysql", "jdbc");

        assertThat(api.get("/healthz", null).status()).isEqualTo(401);
        assertThat(api.get("/actuator/health", null).status()).isEqualTo(401);
    }
}
