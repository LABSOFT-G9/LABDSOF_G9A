package pt.isep.bmc.dispatch.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import pt.isep.bmc.dispatch.adapters.out.calendar.CalendarProperties;

/** The calendar adapter's settings (ADR-006): the simulator's URL and the call timeout. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CalendarProperties.class)
class CalendarConfig {
}
