package pt.isep.bmc.dispatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.support.Fixtures.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.mysql.MySQLContainer;
import pt.isep.bmc.dispatch.DispatchApplication;
import pt.isep.bmc.dispatch.support.Api;
import pt.isep.bmc.dispatch.support.TestDatabase;
import pt.isep.bmc.dispatch.support.TestTokens;

/** US-06: the projections, the catalogue and a saved draft survive a restart (NFR-09). */
class RestartIT {

    private static final String SEEDED_ISSUE = "0199f2a0-0000-7000-8000-00000000f001";
    private static final String SEED_PLUMBING_CO = "0199f2a0-0000-7000-8000-00000000e001";

    @Test
    void aDraftSurvivesARestartAndItsReplacementContinuesFromTheStoredVersion() {
        MySQLContainer mysql = TestDatabase.newContainer();
        mysql.start();
        try {
            TestTokens tokens = TestTokens.get();
            String interventionId;
            try (ConfigurableApplicationContext first = start(mysql)) {
                var created = api(first).post("/interventions", tokens.token(ADMIN_A), UUID.randomUUID().toString(),
                        Map.of("issueId", SEEDED_ISSUE, "contractorId", SEED_PLUMBING_CO,
                                "scopeExtract", "Restart check: leak above the garage light, level -1.",
                                "window", Map.of("start", "2099-11-05T09:00:00Z", "end", "2099-11-05T12:00:00Z"),
                                "terms", "Inspect and repair."));
                assertThat(created.status()).isEqualTo(201);
                interventionId = (String) created.json().get("interventionId");
            }

            try (ConfigurableApplicationContext second = start(mysql)) {
                var read = api(second).get("/interventions/" + interventionId, tokens.token(ADMIN_A));
                assertThat(read.status()).isEqualTo(200);
                assertThat(read.json()).containsEntry("state", "awaiting_approval").containsEntry("version", 1);
                var replaced = api(second).send("PATCH", "/interventions/" + interventionId, tokens.token(ADMIN_A),
                        Map.of("If-Match", "\"1\"", "Idempotency-Key", UUID.randomUUID().toString()),
                        Map.of("contractorId", "0199f2a0-0000-7000-8000-00000000e006"));
                assertThat(replaced.status()).isEqualTo(200);
                assertThat(replaced.json()).containsEntry("version", 2);
            }
        } finally {
            mysql.stop();
        }
    }

    /** With the synthetic seed: a fresh database the way the local stack starts it. */
    static ConfigurableApplicationContext start(MySQLContainer mysql) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("server.port", "0");
        properties.put("management.server.port", "0");
        properties.put("spring.datasource.url",
                mysql.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        properties.put("spring.datasource.username", mysql.getUsername());
        properties.put("spring.datasource.password", mysql.getPassword());
        properties.put("bmc.security.issuer-uri", TestTokens.ISSUER);
        properties.put("bmc.security.jwk-set-uri", TestTokens.get().jwksUri());
        properties.put("bmc.scheduling.enabled", "false");
        properties.put("bmc.seed-synthetic", "true");
        // Command-line arguments: default properties would lose to application.yaml.
        String[] args = properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(DispatchApplication.class).run(args);
    }

    private static Api api(ConfigurableApplicationContext context) {
        return new Api(context.getEnvironment().getRequiredProperty("local.server.port", Integer.class));
    }

}
