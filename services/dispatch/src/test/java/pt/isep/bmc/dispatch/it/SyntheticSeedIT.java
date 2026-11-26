package pt.isep.bmc.dispatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.support.Fixtures.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.mysql.MySQLContainer;
import pt.isep.bmc.dispatch.support.Api;
import pt.isep.bmc.dispatch.support.TestDatabase;
import pt.isep.bmc.dispatch.support.TestTokens;

/**
 * BMC_SEED_SYNTHETIC=true loads the synthetic catalogue and the temporary
 * projection stand-in through Flyway, on a fresh database: the local stack
 * answers US-06 before RabbitMQ is wired.
 */
class SyntheticSeedIT {

    private static MySQLContainer mysql;
    private static ConfigurableApplicationContext context;
    private static Api api;

    @BeforeAll
    static void start() {
        mysql = TestDatabase.newContainer();
        mysql.start();
        context = RestartIT.start(mysql);
        api = new Api(context.getEnvironment().getRequiredProperty("local.server.port", Integer.class));
    }

    @AfterAll
    static void stop() {
        context.close();
        mysql.stop();
    }

    @Test
    void administratorAListsTheSeededContractorsForTheDispatchOnlyIssue() {
        var response = api.get("/contractors?issueId=0199f2a0-0000-7000-8000-00000000f001",
                TestTokens.get().token(ADMIN_A));

        assertThat(response.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.json().get("items");
        assertThat(items).extracting(item -> item.get("displayName"))
                .containsExactly("Synthetic Aqua Plumbers", "Synthetic Plumbing Co.");
        assertThat(items).allSatisfy(item -> assertThat(item).containsEntry("synthetic", true));
    }

    @Test
    void administratorBDoesNotSeeBuildingAsIssue() {
        assertThat(api.get("/contractors?issueId=0199f2a0-0000-7000-8000-00000000f001",
                TestTokens.get().token(ADMIN_B)).status()).isEqualTo(404);
    }

    @Test
    void everySeededContractorIsSyntheticAndOnlyOneHasTheKeycloakAccount() {
        var jdbc = context.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM contractors WHERE synthetic = FALSE OR display_name NOT LIKE 'Synthetic %'")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT id FROM contractors WHERE user_id = :sub").param("sub", CONTRACTOR)
                .query(String.class).list()).containsExactly("0199f2a0-0000-7000-8000-00000000e001");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM membership_projection WHERE role = 'administrator'")
                .query(Long.class).single()).isEqualTo(2);
    }
}
