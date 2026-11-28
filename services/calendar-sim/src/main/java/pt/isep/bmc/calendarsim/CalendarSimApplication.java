package pt.isep.bmc.calendarsim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The calendar simulator (ADR-006): an external calendar that Dispatch books
 * through {@code CalendarPort}, with every failure of technical design 4.5
 * available on demand.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CalendarSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(CalendarSimApplication.class, args);
    }
}
