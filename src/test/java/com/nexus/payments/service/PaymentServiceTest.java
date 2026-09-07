package com.nexus.payments.service;

import com.nexus.payments.client.PaymentProviderClient;
import com.nexus.payments.client.PaymentProviderResult;
import com.nexus.payments.domain.Payment;
import com.nexus.payments.exception.PaymentProviderUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Orchestration only: PaymentPersistenceService and PaymentProviderClient
 * are both mocked, so these tests verify the decisions PaymentService makes
 * (call the provider or not, mark completed/failed or not) rather than
 * persistence or HTTP behavior, which are covered by
 * PaymentPersistenceServiceTest and RestClientPaymentProviderClientTest.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final BigDecimal AMOUNT = new BigDecimal("49.99");
    private static final String CURRENCY = "EUR";

    @Mock
    private PaymentPersistenceService paymentPersistenceService;

    @Mock
    private PaymentProviderClient paymentProviderClient;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentPersistenceService, paymentProviderClient);
    }

    @Test
    void createPayment_marksCompleted_whenProviderSucceeds() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(paymentPersistenceService.createPendingIfAbsent(orderId, userId, AMOUNT, CURRENCY))
                .thenReturn(Optional.of(pendingPaymentWithId(paymentId, orderId, userId)));
        when(paymentProviderClient.processPayment(orderId, userId, AMOUNT, CURRENCY))
                .thenReturn(PaymentProviderResult.success("txn-123"));

        paymentService.createPayment(orderId, userId, AMOUNT, CURRENCY);

        verify(paymentPersistenceService).markCompleted(paymentId, "txn-123");
        verify(paymentPersistenceService, never()).markFailed(any());
    }

    @Test
    void createPayment_marksFailed_whenProviderDeclines() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(paymentPersistenceService.createPendingIfAbsent(orderId, userId, AMOUNT, CURRENCY))
                .thenReturn(Optional.of(pendingPaymentWithId(paymentId, orderId, userId)));
        when(paymentProviderClient.processPayment(orderId, userId, AMOUNT, CURRENCY))
                .thenReturn(PaymentProviderResult.failure());

        paymentService.createPayment(orderId, userId, AMOUNT, CURRENCY);

        verify(paymentPersistenceService).markFailed(paymentId);
        verify(paymentPersistenceService, never()).markCompleted(any(), any());
    }

    @Test
    void createPayment_propagatesException_andLeavesPaymentUntouched_whenProviderUnavailable() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(paymentPersistenceService.createPendingIfAbsent(orderId, userId, AMOUNT, CURRENCY))
                .thenReturn(Optional.of(pendingPaymentWithId(paymentId, orderId, userId)));
        when(paymentProviderClient.processPayment(orderId, userId, AMOUNT, CURRENCY))
                .thenThrow(new PaymentProviderUnavailableException("boom"));

        assertThatThrownBy(() -> paymentService.createPayment(orderId, userId, AMOUNT, CURRENCY))
                .isInstanceOf(PaymentProviderUnavailableException.class);

        verify(paymentPersistenceService, never()).markCompleted(any(), any());
        verify(paymentPersistenceService, never()).markFailed(any());
    }

    @Test
    void createPayment_doesNotCallProvider_whenPaymentAlreadyExistsForOrder() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(paymentPersistenceService.createPendingIfAbsent(orderId, userId, AMOUNT, CURRENCY))
                .thenReturn(Optional.empty());

        paymentService.createPayment(orderId, userId, AMOUNT, CURRENCY);

        verifyNoInteractions(paymentProviderClient);
        verify(paymentPersistenceService, never()).markCompleted(any(), any());
        verify(paymentPersistenceService, never()).markFailed(any());
    }

    private Payment pendingPaymentWithId(UUID paymentId, UUID orderId, UUID userId) {
        Payment payment = new Payment(orderId, userId, AMOUNT, CURRENCY);
        ReflectionTestUtils.setField(payment, "id", paymentId);
        return payment;
    }
}
