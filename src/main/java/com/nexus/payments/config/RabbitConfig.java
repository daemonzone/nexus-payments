package com.nexus.payments.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * nexus-orders owns the full orders.created.queue topology - exchange,
 * queue (including its dead-letter-exchange argument), bindings, and the
 * dead-letter exchange/queue. RabbitMQ requires a queue's declared
 * arguments to be identical everywhere it is declared, so nexus-payments
 * must not redeclare it (or its DLQ) with a Queue bean here: doing so
 * previously caused PRECONDITION_FAILED once nexus-orders' own declaration
 * gained the dead-letter argument. nexus-payments only ever references
 * these queue names as strings - see OrderCreatedListener.
 */
@Configuration
public class RabbitConfig {

    public static final String ORDERS_CREATED_QUEUE = "orders.created.queue";
    public static final String ORDERS_CREATED_DLQ = "orders.created.dlq";

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
