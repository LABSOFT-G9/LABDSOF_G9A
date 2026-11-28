package pt.isep.bmc.dispatch.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.api.Location;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The synthetic seed is a repeatable migration in {@code db/seed}, added only
 * when {@code BMC_SEED_SYNTHETIC=true}: local and dev, never production. It
 * holds the synthetic contractor catalogue (US-06) and, until RabbitMQ is
 * wired (EN-02-02), a stand-in for the projections: the memberships Issue
 * seeds, and one dispatchable issue that exists in Dispatch only.
 */
@Configuration(proxyBeanMethods = false)
class FlywaySeedConfig {

    static final String SEED_LOCATION = "classpath:db/seed";

    @Bean
    FlywayConfigurationCustomizer syntheticSeed(@Value("${bmc.seed-synthetic:false}") boolean seed) {
        return configuration -> {
            if (!seed) {
                return;
            }
            List<Location> locations = new ArrayList<>(Arrays.asList(configuration.getLocations()));
            locations.add(new Location(SEED_LOCATION));
            configuration.locations(locations.toArray(Location[]::new));
        };
    }
}
