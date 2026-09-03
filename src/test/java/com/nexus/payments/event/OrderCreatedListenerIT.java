package com.nexus.payments.event;

import com.nexus.payments.config.RabbitConfig;
import com.nexus.payments.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * End-to-end proof that a message published exactly as nexus-orders
 * publishes it (including its own, foreign __TypeId__ header) is correctly
 * received and deserialized by the real listener wired through Spring Boot's
 * autoconfigured RabbitMQ infrastructure.
 */
@SpringBootTest
class OrderCreatedListenerIT extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @SpyBean
    private OrderCreatedListener listener;

    @Test
    void listener_receivesAndDeserializesEvent_publishedWithProducersTypeId() {
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

        rabbitTemplate.send(RabbitConfig.ORDERS_CREATED_QUEUE, message);

        ArgumentCaptor<OrderCreated> captor = ArgumentCaptor.forClass(OrderCreated.class);
        verify(listener, timeout(5000)).onOrderCreated(captor.capture());

        OrderCreated received = captor.getValue();
        assertThat(received.eventId()).isEqualTo(eventId);
        assertThat(received.orderId()).isEqualTo(orderId);
        assertThat(received.userId()).isEqualTo(userId);
        assertThat(received.totalAmount()).isEqualByComparingTo("49.99");
        assertThat(received.currency()).isEqualTo("EUR");
    }
}
