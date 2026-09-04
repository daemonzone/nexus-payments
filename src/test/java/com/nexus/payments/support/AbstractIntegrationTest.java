package com.nexus.payments.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Base for full-context (@SpringBootTest) tests. Now that JPA/Flyway are
 * configured, any such test needs a real datasource at context startup in
 * addition to the broker. {@link AbstractPostgresIntegrationTest} remains
 * Postgres-only for @DataJpaTest slices that never touch AMQP.
 *
 * Deliberately NOT using @Container-managed fields here: with three or more
 * @SpringBootTest classes sharing this base, JUnit5's Testcontainers
 * extension stops the static containers in afterAll() for each class and
 * the next class's beforeAll() restarts the same (already-stopped) object -
 * repeated restart cycles on one container instance proved unreliable in
 * practice. This is Testcontainers' documented "singleton containers"
 * pattern instead: start once via a static initializer, never stopped
 * between classes, cleaned up by Ryuk when the whole JVM exits.
 */
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("payments_db_test")
                    .withUsername("nexus")
                    .withPassword("nexus");

    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:4.0-management-alpine");

    static {
        POSTGRES.start();
        RABBITMQ.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }
}
