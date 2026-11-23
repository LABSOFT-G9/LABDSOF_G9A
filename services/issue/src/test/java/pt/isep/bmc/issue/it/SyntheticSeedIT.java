package pt.isep.bmc.issue.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import pt.isep.bmc.issue.support.IntegrationTestSupport;
import pt.isep.bmc.issue.support.TestDatabase;

/** BMC_SEED_SYNTHETIC=true loads the synthetic fixtures through Flyway, on a fresh database. */
@SpringBootTest(properties = "bmc.seed-synthetic=true")
@DirtiesContext
class SyntheticSeedIT {

    static final MySQLContainer MYSQL = TestDatabase.newContainer();

    static {
        MYSQL.start();
    }

    @Autowired
    JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry, MYSQL);
    }

    @Test
    void seedCreatesTwoBuildingsAndEveryRole() {
        assertThat(count("buildings")).isEqualTo(2);
        assertThat(count("areas")).isEqualTo(5);
        assertThat(count("users")).isEqualTo(6);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM memberships WHERE role = 'administrator'").query(Long.class).single())
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM memberships WHERE user_id LIKE '%0301'").query(Long.class).single())
                .as("the contractor account has no building membership").isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM users WHERE display_name NOT LIKE 'Synthetic %'")
                .query(Long.class).single()).isZero();
        MYSQL.stop();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
