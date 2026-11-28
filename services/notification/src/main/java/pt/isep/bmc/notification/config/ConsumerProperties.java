package pt.isep.bmc.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retries of the {@code NotificationRequested} consumer, under
 * {@code bmc.notification.consumer}. The defaults are proposals to calibrate
 * (ADR-007, EN01-U7), not measured values.
 *
 * @param maxAttempts attempts per delivery before it is given up as exhausted
 * @param retryDelay  pause between attempts
 */
@ConfigurationProperties("bmc.notification.consumer")
record ConsumerProperties(@DefaultValue("5") int maxAttempts, @DefaultValue("2s") Duration retryDelay) {
}
