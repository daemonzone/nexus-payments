package com.nexus.payments.domain;

import com.nexus.payments.exception.InvalidPaymentStateTransitionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    @Test
    void complete_transitionsToCompleted_andStoresProviderTransactionId_whenPending() {
        Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "EUR");

        payment.complete("txn-123");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getProviderTransactionId()).isEqualTo("txn-123");
    }

    @Test
    void fail_transitionsToFailed_whenPending() {
        Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "EUR");

        payment.fail();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getProviderTransactionId()).isNull();
    }

    @Test
    void complete_throws_whenAlreadyCompleted() {
        Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "EUR");
        payment.complete("txn-123");

        assertThatThrownBy(() -> payment.complete("txn-456"))
                .isInstanceOf(InvalidPaymentStateTransitionException.class);
    }

    @Test
    void complete_throws_whenAlreadyFailed() {
        Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "EUR");
        payment.fail();

        assertThatThrownBy(() -> payment.complete("txn-123"))
                .isInstanceOf(InvalidPaymentStateTransitionException.class);
    }

    @Test
    void fail_throws_whenAlreadyCompleted() {
        Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "EUR");
        payment.complete("txn-123");

        assertThatThrownBy(payment::fail)
                .isInstanceOf(InvalidPaymentStateTransitionException.class);
    }

    @Test
    void fail_throws_whenAlreadyFailed() {
        Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "EUR");
        payment.fail();

        assertThatThrownBy(payment::fail)
                .isInstanceOf(InvalidPaymentStateTransitionException.class);
    }
}
