package pt.isep.bmc.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pt.isep.bmc.messaging.inbox.EventHandler;
import pt.isep.bmc.messaging.inbox.InboxListener;
import pt.isep.bmc.messaging.inbox.ProcessedEventPurger;
import pt.isep.bmc.messaging.outbox.OutboxPublisher;
import pt.isep.bmc.messaging.outbox.OutboxRelay;
import pt.isep.bmc.messaging.support.Stack;

/** The starter as a service gets it: from its properties, data source and RabbitMQ connection. */
class BmcMessagingAutoConfigurationIT {

    private String source;
    private String queue;

    @BeforeEach
    void setUp() {
        Stack.resetDatabase();
        source = "p" + System.nanoTime();
        queue = Stack.declareQueue("autoconfig-it");
        Stack.bind(queue, source + ".#");
    }

    private ApplicationContextRunner service() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        JdbcClientAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class,
                        JacksonAutoConfiguration.class,
                        RabbitAutoConfiguration.class,
                        BmcMessagingAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=" + Stack.jdbcUrl(),
                        "spring.datasource.username=" + Stack.jdbcUsername(),
                        "spring.datasource.password=" + Stack.jdbcPassword(),
                        "spring.rabbitmq.host=" + Stack.rabbitHost(),
                        "spring.rabbitmq.port=" + Stack.rabbitPort(),
                        "spring.rabbitmq.username=" + Stack.rabbitUsername(),
                        "spring.rabbitmq.password=" + Stack.rabbitPassword());
    }

    private static final String[] CONFIRMS = {
        "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true"
    };

    @Test
    void doesNothingUntilAServiceTurnsItOn() {
        service().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OutboxRelay.class);
            assertThat(context).doesNotHaveBean(InboxListener.class);
            assertThat(context).hasSingleBean(MessagingMetrics.class);
        });
    }

    @Test
    void theOutboxRefusesToStartWithoutPublisherConfirmsAndReturns() {
        service().withPropertyValues("bmc.messaging.outbox.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("spring.rabbitmq.publisher-confirm-type=correlated")
                    .hasMessageContaining("spring.rabbitmq.publisher-returns=true");
        });
    }

    @Test
    void anEnabledInboxNeedsAQueue() {
        service().withPropertyValues("bmc.messaging.inbox.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("bmc.messaging.inbox.queue");
        });
    }

    @Test
    void anOutboxRowReachesTheHandlerOfTheConsumingService() {
        service().withUserConfiguration(Handlers.class)
                .withPropertyValues(CONFIRMS)
                .withPropertyValues(
                        "bmc.messaging.outbox.enabled=true",
                        "bmc.messaging.outbox.poll-interval=100ms",
                        "bmc.messaging.inbox.enabled=true",
                        "bmc.messaging.inbox.queue=" + queue)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OutboxPublisher.class).hasSingleBean(ProcessedEventPurger.class);
                    assertThat(context.getBean(OutboxRelay.class).isRunning()).isTrue();
                    assertThat(context.getBean(SimpleMessageListenerContainer.class).isRunning()).isTrue();
                    // The services declare nothing on the broker; the deployment does.
                    assertThat(context.getBeansOfType(Queue.class)).isEmpty();

                    String id = Stack.insertOutboxRow(source, "Noted", Instant.now());

                    Handlers handlers = context.getBean(Handlers.class);
                    await().atMost(Duration.ofSeconds(20))
                            .untilAsserted(() -> assertThat(handlers.seen).containsExactly(id));
                    await().atMost(Duration.ofSeconds(20))
                            .untilAsserted(() -> assertThat(Stack.outboxStatus(id)).isEqualTo("sent"));
                    MessagingMetrics metrics = context.getBean(MessagingMetrics.class);
                    assertThat(metrics.publishedCount()).isEqualTo(1);
                    await().atMost(Duration.ofSeconds(20))
                            .untilAsserted(() -> assertThat(metrics.processedCount()).isEqualTo(1));
                    assertThat(Stack.count("processed_events")).isEqualTo(1);
                });
    }

    @Test
    void aQueueThatIsMissingAtStartDoesNotStopTheService() {
        service().withUserConfiguration(Handlers.class)
                .withPropertyValues(
                        "bmc.messaging.inbox.enabled=true", "bmc.messaging.inbox.queue=not-declared-" + source)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class Handlers {

        final List<String> seen = new CopyOnWriteArrayList<>();

        @Bean
        EventHandler notedHandler() {
            return new EventHandler() {
                @Override
                public String eventType() {
                    return "Noted";
                }

                @Override
                public void handle(CloudEvent event) {
                    seen.add(event.id());
                }
            };
        }
    }
}
