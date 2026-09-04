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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository);
    }

    @Test
    void createPayment_persistsPendingPayment_whenNoPaymentExistsForOrder() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        paymentService.createPayment(orderId, userId, new BigDecimal("49.99"), "EUR");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        Payment saved = captor.getValue();
        assertThat(saved.getOrderId()).isEqualTo(orderId);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getAmount()).isEqualByComparingTo("49.99");
        assertThat(saved.getCurrency()).isEqualTo("EUR");
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void createPayment_isNoOp_whenPaymentAlreadyExistsForOrder() {
        UUID orderId = UUID.randomUUID();
        Payment existing = new Payment(orderId, UUID.randomUUID(), new BigDecimal("49.99"), "EUR");
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(existing));

        paymentService.createPayment(orderId, UUID.randomUUID(), new BigDecimal("49.99"), "EUR");

        verify(paymentRepository, never()).save(any());
    }
}
