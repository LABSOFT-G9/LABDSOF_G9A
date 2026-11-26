package pt.isep.bmc.dispatch.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Boots the whole service on a random port against a real MySQL 8.4 (Flyway
 * migrations included) and the test JWKS. Every test starts from empty tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestSupport {

    /** Tables emptied before each test, children first. */
    private static final String[] TABLES = {"idempotency_keys"};

    @Autowired
    protected Environment environment;

    @Autowired
    protected JdbcClient jdbc;

    protected final TestTokens tokens = TestTokens.get();
    protected Api api;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        register(registry, TestDatabase.shared());
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
    void resetData() {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        api = new Api(environment.getRequiredProperty("local.server.port", Integer.class));
    }

    protected long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
