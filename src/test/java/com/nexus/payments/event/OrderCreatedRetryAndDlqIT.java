package com.nexus.payments.event;

import com.nexus.payments.client.PaymentProviderClient;
import com.nexus.payments.config.RabbitConfig;
import com.nexus.payments.repository.PaymentRepository;
import com.nexus.payments.service.PaymentService;
import com.nexus.payments.support.AbstractIntegrationTest;
import com.nexus.payments.support.TestOrdersTopologyConfig;
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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Proves the real retry + dead-lettering behavior configured for this
 * checkpoint: a listener failure is retried a bounded number of times with
 * backoff, and once exhausted, RabbitMQ dead-letters the message to
 * orders.created.dlq (rather than redelivering forever, as it did before).
 *
 * The failure is a genuine one (totalAmount 0 violates the
 * chk_payments_amount_positive constraint), not a mocked exception:
 * stubbing a void method on a spy of a @Transactional-proxied bean
 * (doThrow().when(spy)...) proved fragile in practice here, so the spy is
 * used only to count real invocations, exactly as in the other listener ITs.
 *
 * @DirtiesContext: the broker (see AbstractIntegrationTest) is a singleton
 * shared across all @SpringBootTest classes in this module. Without closing
 * this context after the class, its listener container would keep competing
 * for messages on orders.created.queue against later test classes' contexts.
 */
@SpringBootTest
@Import(TestOrdersTopologyConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderCreatedRetryAndDlqIT extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @SpyBean
    private PaymentService paymentService;

    // Never actually invoked in this test: the amount=0 constraint violation
    // happens while creating the PENDING payment, before the provider would
    // be called. Still required so the context has a PaymentProviderClient
    // bean instead of the real HTTP-backed one, which would otherwise
    // attempt to reach an unavailable http://localhost:8084 if this test is
    // ever changed to reach that code path.
    @MockBean
    private PaymentProviderClient paymentProviderClient;

    @Test
    void invalidEvent_isRetriedThreeTimesThenDeadLettered() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        String json = ("{\"eventId\":\"%s\",\"orderId\":\"%s\",\"userId\":\"%s\","
                + "\"totalAmount\":0,\"currency\":\"EUR\",\"occurredAt\":\"2024-01-01T00:00:00Z\"}")
                .formatted(eventId, orderId, userId);

        Message message = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setHeader("__TypeId__", "com.nexus.orders.event.OrderCreated")
                .build();

        rabbitTemplate.send(RabbitConfig.ORDERS_CREATED_QUEUE, message);

        // max-attempts=3 with 1s/2s backoff: allow generous headroom before
        // asserting the retries are exhausted.
        verify(paymentService, timeout(15000).times(3)).createPayment(eq(orderId), eq(userId), any(), any());

        Message deadLettered = rabbitTemplate.receive(RabbitConfig.ORDERS_CREATED_DLQ, 5000);
        assertThat(deadLettered).isNotNull();
        assertThat(new String(deadLettered.getBody(), StandardCharsets.UTF_8)).contains(eventId.toString());
        assertThat(new String(deadLettered.getBody(), StandardCharsets.UTF_8)).contains(orderId.toString());

        assertThat(paymentRepository.findByOrderId(orderId)).isEmpty();
        assertThat(rabbitTemplate.receive(RabbitConfig.ORDERS_CREATED_QUEUE, 1000)).isNull();
    }
}
