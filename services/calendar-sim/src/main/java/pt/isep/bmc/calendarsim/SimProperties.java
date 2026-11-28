package pt.isep.bmc.calendarsim;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param controlEnabled whether {@code /control} answers; otherwise it is {@code 404}
 * @param hang           how long {@code timeout_after_create} waits before it answers
 */
@ConfigurationProperties(prefix = "sim")
public record SimProperties(boolean controlEnabled, Duration hang) {

    public SimProperties {
        if (hang == null) {
            hang = Duration.ofSeconds(15);
        }
    }
}
