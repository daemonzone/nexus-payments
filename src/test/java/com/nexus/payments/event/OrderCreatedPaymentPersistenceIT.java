package com.nexus.payments.event;

import com.nexus.payments.config.RabbitConfig;
import com.nexus.payments.domain.Payment;
import com.nexus.payments.domain.PaymentStatus;
import com.nexus.payments.repository.PaymentRepository;
import com.nexus.payments.service.PaymentService;
import com.nexus.payments.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Proves the full flow this checkpoint adds: a message published exactly as
 * nexus-orders publishes it (real broker, real __TypeId__ header from the
 * producer's own class) is received by the real @RabbitListener and results
 * in a PENDING Payment row actually committed to Postgres.
 */
@SpringBootTest
class OrderCreatedPaymentPersistenceIT extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @SpyBean
    private PaymentService paymentService;

    @Test
    void orderCreatedEvent_resultsInPendingPaymentPersistedInPostgres() {
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
        assertThat(saved.get().getStatus()).isEqualTo(PaymentStatus.PENDING);
    }
}
