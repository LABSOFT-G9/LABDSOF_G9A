package pt.isep.bmc.issue.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.api.Location;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Synthetic fixtures (two buildings, residents, an administrator and a
 * contractor account with no membership) are a repeatable migration in
 * {@code db/seed}, added only when {@code BMC_SEED_SYNTHETIC=true}: local and
 * dev, never production. The schema lives in {@code db/migration}.
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
