package pt.isep.bmc.issue.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduled jobs (idempotency key purge); off in tests that need determinism. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "bmc.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {
}
