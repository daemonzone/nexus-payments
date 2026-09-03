package com.nexus.payments.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.payments.config.RabbitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the fix for the real risk in keeping the event contract local
 * (no shared library): nexus-orders tags messages with a __TypeId__ header
 * naming its own class, com.nexus.orders.event.OrderCreated, which does not
 * exist on this classpath. The converter must still deserialize correctly.
 *
 * With TypePrecedence.INFERRED, the converter resolves the target type from
 * MessageProperties.getInferredArgumentType() rather than the __TypeId__
 * header. In production, Spring's @RabbitListener machinery
 * (MessagingMessageListenerAdapter) sets that field from the listener
 * method's parameter type before invoking the converter - this test sets it
 * the same way to exercise the same code path without needing a broker.
 */
class OrderCreatedMessageConverterTest {

    @Test
    void fromMessage_deserializesIntoLocalType_ignoringForeignProducerTypeIdHeader() {
        Jackson2JsonMessageConverter converter = new RabbitConfig().rabbitMessageConverter(new ObjectMapper().findAndRegisterModules());

        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String json = ("{\"eventId\":\"%s\",\"orderId\":\"%s\",\"userId\":\"%s\","
                + "\"totalAmount\":49.99,\"currency\":\"EUR\",\"occurredAt\":\"2024-01-01T00:00:00Z\"}")
                .formatted(eventId, orderId, userId);

        Message message = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setHeader("__TypeId__", "com.nexus.orders.event.OrderCreated")
                .build();
        message.getMessageProperties().setInferredArgumentType(OrderCreated.class);

        Object converted = converter.fromMessage(message);

        assertThat(converted).isInstanceOf(OrderCreated.class);
        OrderCreated event = (OrderCreated) converted;
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.totalAmount()).isEqualByComparingTo("49.99");
        assertThat(event.currency()).isEqualTo("EUR");
    }
}
