package com.nexus.payments.event;

import com.nexus.payments.client.PaymentProviderClient;
import com.nexus.payments.client.PaymentProviderResult;
import com.nexus.payments.config.RabbitConfig;
import com.nexus.payments.support.AbstractIntegrationTest;
import com.nexus.payments.support.TestOrdersTopologyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end proof that a message published exactly as nexus-orders
 * publishes it (including its own, foreign __TypeId__ header) is correctly
 * received and deserialized by the real listener wired through Spring Boot's
 * autoconfigured RabbitMQ infrastructure.
 *
 * @DirtiesContext: the broker (see AbstractIntegrationTest) is a singleton
 * shared across all @SpringBootTest classes in this module. Without closing
 * this context after the class, its listener container would keep competing
 * for messages on orders.created.queue against later test classes' contexts.
 */
@SpringBootTest
@Import(TestOrdersTopologyConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderCreatedListenerIT extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @SpyBean
    private OrderCreatedListener listener;

    @MockBean
    private PaymentProviderClient paymentProviderClient;

    @BeforeEach
    void stubProviderSuccess() {
        // This class only asserts deserialization of the incoming event; the
        // provider call it triggers as a side effect is stubbed out so the
        // test doesn't depend on (or slow down waiting for) a real provider.
        // A fresh id per invocation: provider_transaction_id is unique, and
        // the Postgres container backing these tests (see
        // AbstractIntegrationTest) is a singleton shared by every
        // @SpringBootTest class in this module, so a fixed literal here
        // would collide with the same literal used in another test class.
        when(paymentProviderClient.processPayment(any(), any(), any(), any()))
                .thenReturn(PaymentProviderResult.success(UUID.randomUUID().toString()));
    }

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
