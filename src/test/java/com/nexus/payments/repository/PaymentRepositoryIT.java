package com.nexus.payments.repository;

import com.nexus.payments.domain.Payment;
import com.nexus.payments.domain.PaymentStatus;
import com.nexus.payments.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void savesAndReloadsPayment_withDefaultsPopulated() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Payment payment = new Payment(orderId, userId, new BigDecimal("49.99"), "EUR");

        Payment saved = paymentRepository.saveAndFlush(payment);

        Optional<Payment> reloaded = paymentRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getOrderId()).isEqualTo(orderId);
        assertThat(reloaded.get().getUserId()).isEqualTo(userId);
        assertThat(reloaded.get().getAmount()).isEqualByComparingTo("49.99");
        assertThat(reloaded.get().getCurrency()).isEqualTo("EUR");
        assertThat(reloaded.get().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reloaded.get().getCreatedAt()).isNotNull();
        assertThat(reloaded.get().getUpdatedAt()).isNotNull();
    }

    @Test
    void findByOrderId_returnsPayment_whenExists() {
        UUID orderId = UUID.randomUUID();
        paymentRepository.saveAndFlush(new Payment(orderId, UUID.randomUUID(), new BigDecimal("10.00"), "USD"));

        assertThat(paymentRepository.findByOrderId(orderId)).isPresent();
        assertThat(paymentRepository.findByOrderId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void duplicateOrderId_violatesUniqueConstraint() {
        UUID orderId = UUID.randomUUID();
        paymentRepository.saveAndFlush(new Payment(orderId, UUID.randomUUID(), new BigDecimal("10.00"), "USD"));

        assertThatThrownBy(() ->
                paymentRepository.saveAndFlush(new Payment(orderId, UUID.randomUUID(), new BigDecimal("20.00"), "USD")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persisting_violatesCheckConstraint_whenAmountIsNotPositive() {
        Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("0.00"), "USD");

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(payment))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persisting_violatesCheckConstraint_whenCurrencyIsNotThreeUppercaseLetters() {
        Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "usd");

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(payment))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
