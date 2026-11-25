package pt.isep.bmc.issue.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.api.Location;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pt.isep.bmc.issue.adapters.in.startup.SeededMembershipAnnouncer;
import pt.isep.bmc.issue.application.port.in.AnnounceMembershipsUseCase;

/**
 * Synthetic fixtures (two buildings, residents, an administrator and a
 * contractor account with no membership) are a repeatable migration in
 * {@code db/seed}, added only when {@code BMC_SEED_SYNTHETIC=true}: local and
 * dev, never production. The schema lives in {@code db/migration}.
 *
 * <p>A migration writes no events, so with the seed on the service also
 * announces the seeded memberships at every start (only what is missing).
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

    @Bean
    @ConditionalOnProperty(name = "bmc.seed-synthetic", havingValue = "true")
    SeededMembershipAnnouncer seededMembershipAnnouncer(AnnounceMembershipsUseCase announce) {
        return new SeededMembershipAnnouncer(announce);
    }
}
