package pt.isep.bmc.dispatch.support;

import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** One MySQL 8.4 container (same image and pin as compose.local.yaml) shared by the integration tests. */
public final class TestDatabase {

    public static final DockerImageName IMAGE = DockerImageName
            .parse("mysql:8.4@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902")
            .asCompatibleSubstituteFor("mysql");

    private static MySQLContainer shared;

    private TestDatabase() {
    }

    public static MySQLContainer newContainer() {
        return new MySQLContainer(IMAGE).withDatabaseName("dispatch").withUsername("dispatch").withPassword("dispatch-test");
    }

    public static synchronized MySQLContainer shared() {
        if (shared == null) {
            shared = newContainer();
            shared.start();
        }
        return shared;
    }
}
