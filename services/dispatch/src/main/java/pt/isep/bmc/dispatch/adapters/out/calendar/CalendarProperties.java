package pt.isep.bmc.dispatch.adapters.out.calendar;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl the calendar simulator, for example {@code http://calendar-sim:8080}
 * @param timeout per call, connection included (technical design 4.6: 5 seconds)
 */
@ConfigurationProperties("bmc.calendar")
public record CalendarProperties(String baseUrl, Duration timeout) {

    public CalendarProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://calendar-sim:8080";
        }
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (timeout == null) {
            timeout = Duration.ofSeconds(5);
        }
    }
}
