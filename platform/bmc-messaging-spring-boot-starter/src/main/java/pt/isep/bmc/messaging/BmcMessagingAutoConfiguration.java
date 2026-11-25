package pt.isep.bmc.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pt.isep.bmc.messaging.inbox.EventHandler;
import pt.isep.bmc.messaging.inbox.InboxListener;
import pt.isep.bmc.messaging.inbox.ProcessedEventPurger;
import pt.isep.bmc.messaging.inbox.ProcessedEventStore;
import pt.isep.bmc.messaging.outbox.OutboxPublisher;
import pt.isep.bmc.messaging.outbox.OutboxRelay;
import pt.isep.bmc.messaging.outbox.OutboxStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the outbox publisher ({@code bmc.messaging.outbox.enabled=true}) and
 * the idempotent consumer ({@code bmc.messaging.inbox.enabled=true}) from the
 * service's own data source, transaction manager and RabbitMQ connection.
 *
 * <p>Nothing here declares an exchange, a queue or a binding: the deployment
 * declares them, and the services' broker users may not (ADR-003, item 2).
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
    "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
    "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"
})
@EnableConfigurationProperties(MessagingProperties.class)
public class BmcMessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MessagingMetrics bmcMessagingMetrics(ObjectProvider<MeterRegistry> registry) {
        return new MessagingMetrics(registry.getIfAvailable());
    }

    private static Clock clock(ObjectProvider<Clock> clock) {
        return clock.getIfAvailable(Clock::systemUTC);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "bmc.messaging.outbox", name = "enabled", havingValue = "true")
    static class OutboxConfiguration {

        @Bean
        @ConditionalOnMissingBean
        OutboxStore bmcOutboxStore(JdbcClient jdbc) {
            return new OutboxStore(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean
        OutboxPublisher bmcOutboxPublisher(
                OutboxStore store,
                ConnectionFactory connectionFactory,
                PlatformTransactionManager transactionManager,
                MessagingMetrics metrics,
                ObjectProvider<Clock> clock,
                MessagingProperties properties) {
            if (!(connectionFactory instanceof CachingConnectionFactory caching)
                    || !caching.isPublisherConfirms()
                    || !caching.isPublisherReturns()) {
                throw new IllegalStateException(
                        "The outbox publisher needs correlated publisher confirms and returns: set"
                                + " spring.rabbitmq.publisher-confirm-type=correlated and"
                                + " spring.rabbitmq.publisher-returns=true");
            }
            RabbitTemplate rabbit = new RabbitTemplate(connectionFactory);
            rabbit.setMandatory(true);
            MessagingProperties.Outbox outbox = properties.outbox();
            return new OutboxPublisher(
                    store,
                    rabbit,
                    new TransactionTemplate(transactionManager),
                    metrics,
                    clock(clock),
                    properties.exchange(),
                    outbox.batchSize(),
                    outbox.confirmTimeout(),
                    outbox.notPublishedTypes());
        }

        @Bean
        @ConditionalOnMissingBean
        OutboxRelay bmcOutboxRelay(OutboxPublisher publisher, MessagingProperties properties) {
            MessagingProperties.Outbox outbox = properties.outbox();
            return new OutboxRelay(publisher, outbox.pollInterval(), outbox.backoffInitial(), outbox.backoffMax());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "bmc.messaging.inbox", name = "enabled", havingValue = "true")
    static class InboxConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ProcessedEventStore bmcProcessedEventStore(JdbcClient jdbc) {
            return new ProcessedEventStore(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean
        InboxListener bmcInboxListener(
                ObjectProvider<EventHandler> handlers,
                ProcessedEventStore processedEvents,
                PlatformTransactionManager transactionManager,
                JsonMapper json,
                MessagingMetrics metrics,
                ObjectProvider<Clock> clock,
                MessagingProperties properties) {
            MessagingProperties.Inbox inbox = properties.inbox();
            return new InboxListener(
                    handlers.orderedStream().toList(),
                    processedEvents,
                    new TransactionTemplate(transactionManager),
                    json,
                    metrics,
                    clock(clock),
                    inbox.supportedSchemaVersions(),
                    inbox.retryDelay());
        }

        /**
         * Manual acknowledgement after the commit. The queue is declared by the
         * deployment: nothing is declared here, and a queue that is missing at
         * start does not stop the service; the container keeps trying.
         */
        @Bean
        @ConditionalOnMissingBean(name = "bmcInboxContainer")
        SimpleMessageListenerContainer bmcInboxContainer(
                ConnectionFactory connectionFactory, InboxListener listener, MessagingProperties properties) {
            MessagingProperties.Inbox inbox = properties.inbox();
            SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
            container.setQueueNames(inbox.queue());
            container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            container.setPrefetchCount(inbox.prefetch());
            container.setAutoDeclare(false);
            container.setMissingQueuesFatal(false);
            container.setAutoStartup(inbox.autoStartup());
            container.setMessageListener(listener);
            return container;
        }

        @Bean
        @ConditionalOnMissingBean
        ProcessedEventPurger bmcProcessedEventPurger(
                ProcessedEventStore processedEvents, ObjectProvider<Clock> clock, MessagingProperties properties) {
            MessagingProperties.Inbox inbox = properties.inbox();
            return new ProcessedEventPurger(
                    processedEvents, clock(clock), inbox.processedRetention(), inbox.purgeInterval());
        }
    }
}
