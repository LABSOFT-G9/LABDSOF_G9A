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
        for (String table : new String[] {"outbox", "idempotency_keys", "issue_history", "reports", "issues"}) {
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

    protected long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
