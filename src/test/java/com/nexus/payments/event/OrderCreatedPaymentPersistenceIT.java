package com.nexus.payments.event;

import com.nexus.payments.client.PaymentProviderClient;
import com.nexus.payments.client.PaymentProviderResult;
import com.nexus.payments.config.RabbitConfig;
import com.nexus.payments.domain.Payment;
import com.nexus.payments.domain.PaymentStatus;
import com.nexus.payments.repository.PaymentRepository;
import com.nexus.payments.service.PaymentService;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the full flow this checkpoint adds: a message published exactly as
 * nexus-orders publishes it (real broker, real __TypeId__ header from the
 * producer's own class) is received by the real @RabbitListener, calls the
 * (mocked - no real nexus-payment-provider runs in this test) provider, and
 * results in a COMPLETED Payment row actually committed to Postgres.
 *
 * PaymentProviderClient is mocked rather than pointed at a real provider:
 * this test's job is proving the RabbitMQ -> listener -> service -> Postgres
 * wiring, not the HTTP client itself (see RestClientPaymentProviderClientTest
 * for that).
 *
 * @DirtiesContext: the broker (see AbstractIntegrationTest) is a singleton
 * shared across all @SpringBootTest classes in this module. Without closing
 * this context after the class, its listener container would keep competing
 * for messages on orders.created.queue against later test classes' contexts.
 */
@SpringBootTest
@Import(TestOrdersTopologyConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderCreatedPaymentPersistenceIT extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @SpyBean
    private PaymentService paymentService;

    @MockBean
    private PaymentProviderClient paymentProviderClient;

    private String stubbedProviderTransactionId;

    @BeforeEach
    void stubProviderSuccess() {
        // A fresh id per test: provider_transaction_id is unique, and the
        // Postgres container backing these tests (see
        // AbstractIntegrationTest) is a singleton shared by every
        // @SpringBootTest class in this module, so a fixed literal here
        // would collide with the same literal used in another test class.
        stubbedProviderTransactionId = UUID.randomUUID().toString();
        when(paymentProviderClient.processPayment(any(), any(), any(), any()))
                .thenReturn(PaymentProviderResult.success(stubbedProviderTransactionId));
    }

    @Test
    void orderCreatedEvent_resultsInCompletedPaymentPersistedInPostgres() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String json = ("{\"eventId\":\"%s\",\"orderId\":\"%s\",\"userId\":\"%s\","
                + "\"totalAmount\":49.99,\"currency\":\"EUR\",\"occurredAt\":\"2024-01-01T00:00:00Z\"}")
                .formatted(UUID.randomUUID(), orderId, userId);

        Message message = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setHeader("__TypeId__", "com.nexus.orders.event.OrderCreated")
                .build();

        rabbitTemplate.send(RabbitConfig.ORDERS_CREATED_QUEUE, message);

        verify(paymentService, timeout(5000)).createPayment(any(), any(), any(), any());

        Optional<Payment> saved = paymentRepository.findByOrderId(orderId);
        assertThat(saved).isPresent();
        assertThat(saved.get().getUserId()).isEqualTo(userId);
        assertThat(saved.get().getAmount()).isEqualByComparingTo("49.99");
        assertThat(saved.get().getCurrency()).isEqualTo("EUR");
        assertThat(saved.get().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(saved.get().getProviderTransactionId()).isEqualTo(stubbedProviderTransactionId);
    }

    @Test
    void orderCreatedEvent_deliveredTwice_resultsInExactlyOnePaymentPersisted_andProviderCalledOnce() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String json = ("{\"eventId\":\"%s\",\"orderId\":\"%s\",\"userId\":\"%s\","
                + "\"totalAmount\":49.99,\"currency\":\"EUR\",\"occurredAt\":\"2024-01-01T00:00:00Z\"}")
                .formatted(UUID.randomUUID(), orderId, userId);

        Message message = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setHeader("__TypeId__", "com.nexus.orders.event.OrderCreated")
                .build();

        // First delivery: creates the Payment and calls the provider.
        rabbitTemplate.send(RabbitConfig.ORDERS_CREATED_QUEUE, message);
        verify(paymentService, timeout(5000)).createPayment(eq(orderId), eq(userId), any(), any());

        // Simulated redelivery of the exact same event (e.g. an ack lost after
        // successful processing). Must not throw, must not insert a second
        // row - findByOrderId itself would fail with a non-unique-result
        // error if it did - and, crucially, must NOT call the provider again.
        rabbitTemplate.send(RabbitConfig.ORDERS_CREATED_QUEUE, message);
        verify(paymentService, timeout(5000).times(2)).createPayment(eq(orderId), eq(userId), any(), any());

        verify(paymentProviderClient, timeout(1000)).processPayment(eq(orderId), eq(userId), any(), any());

        Optional<Payment> saved = paymentRepository.findByOrderId(orderId);
        assertThat(saved).isPresent();
        assertThat(saved.get().getUserId()).isEqualTo(userId);
        assertThat(saved.get().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }
}
