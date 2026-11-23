package pt.isep.bmc.issue.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.issue.support.Fixtures.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.mysql.MySQLContainer;
import pt.isep.bmc.issue.IssueApplication;
import pt.isep.bmc.issue.support.Api;
import pt.isep.bmc.issue.support.TestDatabase;
import pt.isep.bmc.issue.support.TestTokens;

/** US-01: the author retrieves the report after a backend restart (NFR-09). */
class RestartIT {

    @Test
    void reportSurvivesARestart() throws Exception {
        MySQLContainer mysql = TestDatabase.shared();
        TestTokens tokens = TestTokens.get();
        String text = "Restart check: water dripping in the garage, level -1.";
        String reportId;

        try (ConfigurableApplicationContext first = start(mysql)) {
            try (var connection = first.getBean(javax.sql.DataSource.class).getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("fixtures/us01-fixtures.sql"));
            }
            var created = api(first).post("/reports", tokens.token(RESIDENT_A1), UUID.randomUUID().toString(),
                    Map.of("buildingId", BUILDING_A, "areaId", AREA_A_GARAGE, "text", text));
            assertThat(created.status()).isEqualTo(201);
            reportId = (String) created.json().get("reportId");
        }

        try (ConfigurableApplicationContext second = start(mysql)) {
            var read = api(second).get("/reports/" + reportId, tokens.token(RESIDENT_A1));
            assertThat(read.status()).isEqualTo(200);
            assertThat(read.json()).containsEntry("text", text)
                    .containsEntry("issue", Map.of("state", "received", "version", 1));
        }
    }

    static ConfigurableApplicationContext start(MySQLContainer mysql, String... extraArgs) {
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
        // Command-line arguments: default properties would lose to application.yaml.
        String[] args = java.util.stream.Stream.concat(
                        properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()),
                        java.util.Arrays.stream(extraArgs))
                .toArray(String[]::new);
        return new SpringApplicationBuilder(IssueApplication.class).run(args);
    }

    private static Api api(ConfigurableApplicationContext context) {
        return new Api(context.getEnvironment().getRequiredProperty("local.server.port", Integer.class));
    }
}
