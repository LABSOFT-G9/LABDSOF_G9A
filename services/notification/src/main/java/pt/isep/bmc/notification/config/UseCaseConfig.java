package pt.isep.bmc.notification.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pt.isep.bmc.notification.adapters.in.messaging.NotificationRequestedHandler;
import pt.isep.bmc.notification.application.command.NotificationFacade;
import pt.isep.bmc.notification.application.command.ReadStateService;
import pt.isep.bmc.notification.application.port.out.DeliveryLog;
import pt.isep.bmc.notification.application.port.out.IdGenerator;
import pt.isep.bmc.notification.application.port.out.Inbox;
import pt.isep.bmc.notification.application.port.out.NotificationChannel;
import pt.isep.bmc.notification.application.port.out.TransactionRunner;
import pt.isep.bmc.notification.application.query.NotificationQueryService;

/** Registers the use cases, built from their ports, and the consumer of {@code NotificationRequested}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsumerProperties.class)
class UseCaseConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Every {@link NotificationChannel} bean is a channel: only the in-app adapter in US-10-01. */
    @Bean
    NotificationFacade notificationFacade(
            TransactionRunner transactions,
            DeliveryLog deliveries,
            List<NotificationChannel> channels,
            IdGenerator ids,
            Clock clock) {
        return new NotificationFacade(transactions, deliveries, channels, ids, clock);
    }

    @Bean
    NotificationQueryService notificationQueryService(TransactionRunner transactions, Inbox store) {
        return new NotificationQueryService(transactions, store);
    }

    @Bean
    ReadStateService readStateService(TransactionRunner transactions, Inbox inbox, Clock clock) {
        return new ReadStateService(transactions, inbox, clock);
    }

    @Bean
    NotificationRequestedHandler notificationRequestedHandler(
            NotificationFacade receive, MeterRegistry meters, ConsumerProperties consumer) {
        return new NotificationRequestedHandler(receive, meters, consumer.maxAttempts(), consumer.retryDelay());
    }
}
