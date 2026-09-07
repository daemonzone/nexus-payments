package com.nexus.payments.service;

import com.nexus.payments.domain.Payment;
import com.nexus.payments.domain.PaymentStatus;
import com.nexus.payments.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentPersistenceServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    private PaymentPersistenceService paymentPersistenceService;

    @BeforeEach
    void setUp() {
        paymentPersistenceService = new PaymentPersistenceService(paymentRepository);
    }

    @Test
    void createPendingIfAbsent_persistsPendingPayment_whenNoPaymentExistsForOrder() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<Payment> created = paymentPersistenceService.createPendingIfAbsent(
                orderId, userId, new BigDecimal("49.99"), "EUR");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        Payment saved = captor.getValue();
        assertThat(created).contains(saved);
        assertThat(saved.getOrderId()).isEqualTo(orderId);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getAmount()).isEqualByComparingTo("49.99");
        assertThat(saved.getCurrency()).isEqualTo("EUR");
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void createPendingIfAbsent_returnsEmpty_whenPaymentAlreadyExistsForOrder() {
        UUID orderId = UUID.randomUUID();
        Payment existing = new Payment(orderId, UUID.randomUUID(), new BigDecimal("49.99"), "EUR");
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(existing));

        Optional<Payment> created = paymentPersistenceService.createPendingIfAbsent(
                orderId, UUID.randomUUID(), new BigDecimal("49.99"), "EUR");

        assertThat(created).isEmpty();
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void markCompleted_transitionsPaymentToCompleted_withProviderTransactionId() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "EUR");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        paymentPersistenceService.markCompleted(paymentId, "txn-123");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getProviderTransactionId()).isEqualTo("txn-123");
    }

    @Test
    void markFailed_transitionsPaymentToFailed() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "EUR");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        paymentPersistenceService.markFailed(paymentId);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void markCompleted_throws_whenPaymentNotFound() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentPersistenceService.markCompleted(paymentId, "txn-123"))
                .isInstanceOf(IllegalStateException.class);
    }
}
