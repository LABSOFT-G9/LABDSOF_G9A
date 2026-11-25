package pt.isep.bmc.messaging.support;

import com.rabbitmq.client.Channel;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One MySQL 8.4 and one RabbitMQ 4.4 container shared by the integration
 * tests, with the helpers to declare a topology the way the deployment will
 * (ADR-003, item 2), to write outbox rows and to look into queues.
 */
public final class Stack {

    /** Same image and pin as compose.local.yaml and services/issue. */
    private static final DockerImageName MYSQL = DockerImageName
            .parse("mysql:8.4@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902")
            .asCompatibleSubstituteFor("mysql");

    /** rabbitmq:4.4-management (4.4.1), digest read 2026-11-24. */
    private static final DockerImageName RABBITMQ = DockerImageName
            .parse("rabbitmq:4.4-management@sha256:61f90c401a5b5ed2486771973ea92ad66046471eacab9c9149f1a6e091c91677")
            .asCompatibleSubstituteFor("rabbitmq");

    public static final String EXCHANGE = "bmc.events";
    public static final String DEAD_LETTER_EXCHANGE = "bmc.dlx";
    public static final int DELIVERY_LIMIT = 5;

    private static MySQLContainer mysql;
    private static RabbitMQContainer rabbitmq;
    private static HikariDataSource dataSource;
    private static CachingConnectionFactory admin;

    private Stack() {
    }

    private static synchronized void start() {
        if (mysql != null) {
            return;
        }
        mysql = new MySQLContainer(MYSQL).withDatabaseName("messaging").withUsername("messaging")
                .withPassword("messaging-test");
        rabbitmq = new RabbitMQContainer(RABBITMQ);
        mysql.start();
        rabbitmq.start();

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(mysql.getJdbcUrl());
        dataSource.setUsername(mysql.getUsername());
        dataSource.setPassword(mysql.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);

        admin = connectionFactory();
        try (Connection connection = admin.createConnection(); Channel channel = connection.createChannel(false)) {
            channel.exchangeDeclare(EXCHANGE, "topic", true);
            channel.exchangeDeclare(DEAD_LETTER_EXCHANGE, "direct", true);
        } catch (IOException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String jdbcUrl() {
        start();
        return mysql.getJdbcUrl();
    }

    public static String jdbcUsername() {
        start();
        return mysql.getUsername();
    }

    public static String jdbcPassword() {
        start();
        return mysql.getPassword();
    }

    public static String rabbitHost() {
        start();
        return rabbitmq.getHost();
    }

    public static int rabbitPort() {
        start();
        return rabbitmq.getAmqpPort();
    }

    public static String rabbitUsername() {
        start();
        return rabbitmq.getAdminUsername();
    }

    public static String rabbitPassword() {
        start();
        return rabbitmq.getAdminPassword();
    }

    public static DataSource dataSource() {
        start();
        return dataSource;
    }

    public static JdbcClient jdbc() {
        return JdbcClient.create(dataSource());
    }

    public static TransactionTemplate transaction() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource()));
    }

    /** A connection factory as the starter needs it: correlated confirms and returns. */
    public static CachingConnectionFactory connectionFactory() {
        start();
        return connectionFactory(rabbitmq.getHost(), rabbitmq.getAmqpPort());
    }

    public static CachingConnectionFactory connectionFactory(String host, int port) {
        CachingConnectionFactory factory = new CachingConnectionFactory(host, port);
        factory.setUsername(rabbitmq == null ? "guest" : rabbitmq.getAdminUsername());
        factory.setPassword(rabbitmq == null ? "guest" : rabbitmq.getAdminPassword());
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        return factory;
    }

    /** Empties the three tables. */
    public static void resetDatabase() {
        JdbcClient jdbc = jdbc();
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM processed_events").update();
        jdbc.sql("DELETE FROM effects").update();
    }

    /**
     * Declares a quorum queue with the delivery limit and at-least-once
     * dead-lettering of ADR-003, and its dead-letter queue.
     *
     * @return the queue name, unique per call
     */
    public static String declareQueue(String prefix) {
        start();
        String queue = prefix + "." + UUID.randomUUID();
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("x-queue-type", "quorum");
        arguments.put("x-delivery-limit", DELIVERY_LIMIT);
        arguments.put("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE);
        arguments.put("x-dead-letter-routing-key", deadLetterQueue(queue));
        arguments.put("x-dead-letter-strategy", "at-least-once");
        arguments.put("x-overflow", "reject-publish");
        Map<String, Object> deadLetterArguments = new HashMap<>();
        deadLetterArguments.put("x-queue-type", "quorum");
        deadLetterArguments.put("x-message-ttl", 7L * 24 * 60 * 60 * 1000);
        try (Connection connection = admin.createConnection(); Channel channel = connection.createChannel(false)) {
            channel.queueDeclare(queue, true, false, false, arguments);
            channel.queueDeclare(deadLetterQueue(queue), true, false, false, deadLetterArguments);
            channel.queueBind(deadLetterQueue(queue), DEAD_LETTER_EXCHANGE, deadLetterQueue(queue));
        } catch (IOException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(e);
        }
        return queue;
    }

    public static String deadLetterQueue(String queue) {
        return queue + ".dlq";
    }

    public static void bind(String queue, String routingKey) {
        try (Connection connection = admin.createConnection(); Channel channel = connection.createChannel(false)) {
            channel.queueBind(queue, EXCHANGE, routingKey);
        } catch (IOException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Takes the next message of a queue, waiting up to {@code timeoutMillis}; null if none came. */
    public static Message receive(String queue, long timeoutMillis) {
        return new RabbitTemplate(admin).receive(queue, timeoutMillis);
    }

    /** Publishes a body to the events exchange, as another service's publisher would. */
    public static void publish(String routingKey, String body) {
        Message message = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/cloudevents+json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        new RabbitTemplate(admin).send(EXCHANGE, routingKey, message);
    }

    /** Inserts a pending outbox row the way a service does; returns the event ID. */
    public static String insertOutboxRow(String source, String type, Instant createdAt) {
        String id = UUID.randomUUID().toString();
        jdbc().sql("""
                        INSERT INTO outbox
                          (id, event_type, routing_key, aggregate_id, aggregate_version, building_id,
                           correlation_id, payload, status, attempts, created_at)
                        VALUES (:id, :type, :routingKey, :aggregateId, 1, :buildingId, 'c-test', :payload,
                                'pending', 0, :createdAt)
                        """)
                .param("id", id)
                .param("type", type)
                .param("routingKey", source + "." + type)
                .param("aggregateId", UUID.randomUUID().toString())
                .param("buildingId", UUID.randomUUID().toString())
                .param("payload", envelope(id, source, type, 1, "{\"note\":\"" + type + "\"}"))
                .param("createdAt", LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update();
        return id;
    }

    /** A CloudEvents envelope as the services write it (technical design 4.4). */
    public static String envelope(String id, String source, String type, int schemaVersion, String dataJson) {
        return """
                {"specversion":"1.0","id":"%s","type":"%s","source":"%s",
                 "subject":"0199f2a1-0000-7000-8000-000000002001","time":"2026-11-24T10:00:00Z",
                 "datacontenttype":"application/json","schemaversion":%d,"aggregateversion":3,
                 "buildingid":"0199f2a0-0000-7000-8000-00000000b001","correlationid":"c-test","data":%s}
                """.formatted(id, type, source, schemaVersion, dataJson);
    }

    public static String outboxStatus(String id) {
        return jdbc().sql("SELECT status FROM outbox WHERE id = :id").param("id", id).query(String.class).single();
    }

    public static int outboxAttempts(String id) {
        return jdbc().sql("SELECT attempts FROM outbox WHERE id = :id").param("id", id).query(Integer.class).single();
    }

    public static long count(String table) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
