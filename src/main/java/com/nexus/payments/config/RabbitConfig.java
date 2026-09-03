package com.nexus.payments.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String ORDERS_CREATED_QUEUE = "orders.created.queue";

    /**
     * nexus-orders owns orders.exchange and the order.created binding - that
     * topology is its public producer contract. nexus-payments only needs
     * the queue it consumes from to exist, and declares it independently
     * (idempotently) so it does not need nexus-orders to have started first.
     */
    @Bean
    Queue ordersCreatedQueue() {
        return new Queue(ORDERS_CREATED_QUEUE);
    }

    /**
     * The producer (nexus-orders) tags each message with a __TypeId__ header
     * naming its own class, com.nexus.orders.event.OrderCreated, which does
     * not exist on this classpath. INFERRED precedence makes the converter
     * ignore that header and instead use the @RabbitListener method's
     * parameter type, so the event contract can stay local to each service.
     */
    @Bean
    public Jackson2JsonMessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        converter.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        return converter;
    }
}
