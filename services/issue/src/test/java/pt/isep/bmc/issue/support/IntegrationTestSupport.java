package pt.isep.bmc.issue.support;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Boots the whole service on a random port against a real MySQL 8.4 (Flyway
 * migrations included) and the test JWKS. Every test starts from the synthetic
 * fixtures with no reports.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestSupport {

    protected static final String TEXT = "Water dripping from the ceiling light in the garage, level -1.";

    @Autowired
    protected Environment environment;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    private javax.sql.DataSource dataSource;

    protected final TestTokens tokens = TestTokens.get();
    protected Api api;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MySQLContainer mysql = TestDatabase.shared();
        register(registry, mysql);
    }

    public static void register(DynamicPropertyRegistry registry, MySQLContainer mysql) {
        registry.add("spring.datasource.url", () -> mysql.getJdbcUrl()
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("bmc.security.issuer-uri", () -> TestTokens.ISSUER);
        registry.add("bmc.security.jwk-set-uri", () -> TestTokens.get().jwksUri());
        registry.add("bmc.scheduling.enabled", () -> "false");
        registry.add("management.server.port", () -> "0");
    }

    @BeforeEach
    void resetData() throws Exception {
        for (String table : new String[] {
            "outbox", "idempotency_keys", "triage_decisions", "issue_history", "reports", "issues"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("fixtures/us01-fixtures.sql"));
        }
        api = new Api(environment.getRequiredProperty("local.server.port", Integer.class));
    }

    protected Map<String, Object> report(String buildingId, String areaId, String text) {
        return Map.of("buildingId", buildingId, "areaId", areaId, "text", text);
    }

    /** Submits a report and returns the ID of the issue it created. */
    protected String submitIssue(String sub, String buildingId, String areaId) {
        var response = api.post("/reports", tokens.token(sub), java.util.UUID.randomUUID().toString(),
                report(buildingId, areaId, TEXT));
        if (response.status() != 201) {
            throw new IllegalStateException("report not created: " + response.status());
        }
        return (String) response.json().get("issueId");
    }

    /** {@code POST /issues/{id}/triage} with {@code If-Match: "<version>"} and a fresh idempotency key. */
    protected pt.isep.bmc.issue.support.Api.Response triage(
            String sub, String issueId, long version, String category, String urgency, String reason) {
        return triage(sub, issueId, "\"" + version + "\"", java.util.UUID.randomUUID().toString(),
                triageBody(category, urgency, reason));
    }

    protected pt.isep.bmc.issue.support.Api.Response triage(
            String sub, String issueId, String ifMatch, String idempotencyKey, Object body) {
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("If-Match", ifMatch);
        headers.put("Idempotency-Key", idempotencyKey);
        return api.postWithHeaders("/issues/" + issueId + "/triage", tokens.token(sub), headers, body);
    }

    /**
     * Revokes or restores the administrator memberships of {@code sub}. The
     * fixtures do not reset memberships, so a test that revokes restores in a
     * {@code finally}.
     */
    protected void setAdministratorStatus(String sub, String status) {
        jdbc.sql("UPDATE memberships SET status = :status WHERE user_id = :user AND role = 'administrator'")
                .param("status", status)
                .param("user", sub)
                .update();
    }

    protected Map<String, Object> triageBody(String category, String urgency, String reason) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("category", category);
        body.put("urgency", urgency);
        body.put("reason", reason);
        return body;
    }

    protected long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
