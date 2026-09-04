package com.nexus.payments.support;

import com.nexus.payments.config.RabbitConfig;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * In production, nexus-orders is the sole declarer of orders.exchange,
 * orders.created.queue (with its dead-letter-exchange argument),
 * orders.created.dlx and orders.created.dlq - nexus-payments only ever
 * consumes from names it already knows (see RabbitConfig).
 *
 * nexus-payments' own integration tests run against an isolated,
 * empty Testcontainers broker with no nexus-orders present to declare that
 * topology, so this test-only configuration recreates it - mirroring
 * nexus-orders' RabbitConfig - purely so these tests have something to
 * publish to and consume from. Import this explicitly on tests that need a
 * fully wired broker; it must never be reused as, or copied into,
 * production configuration.
 */
@TestConfiguration
public class TestOrdersTopologyConfig {

    private static final String ORDERS_EXCHANGE = "orders.exchange";
    private static final String ORDER_CREATED_ROUTING_KEY = "order.created";
    private static final String ORDERS_CREATED_DLX = "orders.created.dlx";

    @Bean
    TopicExchange ordersExchange() {
        return new TopicExchange(ORDERS_EXCHANGE);
    }

    @Bean
    Queue ordersCreatedQueue() {
        return QueueBuilder.durable(RabbitConfig.ORDERS_CREATED_QUEUE)
                .deadLetterExchange(ORDERS_CREATED_DLX)
                .build();
    }

    @Bean
    Binding orderCreatedBinding(Queue ordersCreatedQueue, TopicExchange ordersExchange) {
        return BindingBuilder.bind(ordersCreatedQueue).to(ordersExchange).with(ORDER_CREATED_ROUTING_KEY);
    }

    @Bean
    FanoutExchange ordersCreatedDeadLetterExchange() {
        return new FanoutExchange(ORDERS_CREATED_DLX);
    }

    @Bean
    Queue ordersCreatedDeadLetterQueue() {
        return QueueBuilder.durable(RabbitConfig.ORDERS_CREATED_DLQ).build();
    }

    @Bean
    Binding ordersCreatedDeadLetterBinding(Queue ordersCreatedDeadLetterQueue,
                                            FanoutExchange ordersCreatedDeadLetterExchange) {
        return BindingBuilder.bind(ordersCreatedDeadLetterQueue).to(ordersCreatedDeadLetterExchange);
    }
}
