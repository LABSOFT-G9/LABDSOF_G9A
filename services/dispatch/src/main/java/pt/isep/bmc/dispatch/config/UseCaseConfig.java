package pt.isep.bmc.dispatch.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pt.isep.bmc.dispatch.application.command.PurgeExpiredIdempotencyKeysService;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyStore;
import pt.isep.bmc.dispatch.application.port.out.TransactionRunner;

/** Registers the use cases, built from their ports. */
@Configuration(proxyBeanMethods = false)
class UseCaseConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PurgeExpiredIdempotencyKeysService purgeExpiredIdempotencyKeysService(
            TransactionRunner transactions, IdempotencyStore idempotency, Clock clock) {
        return new PurgeExpiredIdempotencyKeysService(
                transactions, idempotency, clock, PurgeExpiredIdempotencyKeysService.DEFAULT_BATCH_SIZE);
    }
}
