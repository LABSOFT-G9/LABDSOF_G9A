package pt.isep.bmc.issue.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.issue.support.Fixtures.*;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import pt.isep.bmc.issue.support.Api;
import pt.isep.bmc.issue.support.IntegrationTestSupport;
import pt.isep.bmc.issue.support.TestDatabase;
import pt.isep.bmc.issue.support.TestTokens;

/**
 * US-01: if storage fails, no receipt is claimed. Uses its own MySQL, stopped
 * mid-test; works with no AI and no broker at all (NFR-03, NFR-04).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.hikari.connection-timeout=2000")
@DirtiesContext
class StorageFailureIT {

    static final MySQLContainer MYSQL = TestDatabase.newContainer();

    static {
        MYSQL.start();
    }

    @Autowired
    Environment environment;

    @Autowired
    javax.sql.DataSource dataSource;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry, MYSQL);
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    @Test
    void databaseDownAnswers503WithoutAReceipt() throws Exception {
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("fixtures/us01-fixtures.sql"));
        }
        Api api = new Api(environment.getRequiredProperty("local.server.port", Integer.class));
        String token = TestTokens.get().token(RESIDENT_A1);
        Map<String, String> body = Map.of("buildingId", BUILDING_A, "areaId", AREA_A_GARAGE,
                "text", "Water dripping from the ceiling light in the garage.");
        assertThat(api.post("/reports", token, UUID.randomUUID().toString(), body).status()).isEqualTo(201);

        MYSQL.stop();

        var failed = api.post("/reports", token, UUID.randomUUID().toString(), body);
        assertThat(failed.status()).isEqualTo(503);
        assertThat(failed.json()).containsEntry("code", "DEPENDENCY_UNAVAILABLE").doesNotContainKey("reportId");
        assertThat(failed.header("Retry-After")).isEqualTo("5");
        assertThat(api.get("/reports", token).status()).isEqualTo(503);
    }
}
