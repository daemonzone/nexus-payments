package com.nexus.payments.service;

import com.nexus.payments.domain.Payment;
import com.nexus.payments.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Holds the three short, independent database transactions PaymentService's
 * flow needs. Kept as a separate bean (not just @Transactional methods on
 * PaymentService) so each one goes through Spring's transactional proxy
 * correctly - calling an @Transactional method on `this` from within the
 * same class silently skips the proxy, which would make these no-ops.
 */
@Service
class PaymentPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(PaymentPersistenceService.class);

    private final PaymentRepository paymentRepository;

    PaymentPersistenceService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /**
     * Idempotent by orderId: a redelivered OrderCreated for an order that
     * already has a Payment returns empty, telling the caller to treat the
     * event as already handled. This is a check-then-act guard, not a
     * race-proof lock - see the uq_payments_order_id constraint, which
     * remains the final integrity guarantee if two deliveries are ever
     * processed concurrently.
     */
    @Transactional
    Optional<Payment> createPendingIfAbsent(UUID orderId, UUID userId, BigDecimal amount, String currency) {
        if (paymentRepository.findByOrderId(orderId).isPresent()) {
            return Optional.empty();
        }

        Payment payment = new Payment(orderId, userId, amount, currency);
        Payment saved = paymentRepository.save(payment);
        log.info("Payment created, paymentId={}, orderId={}, status={}",
                saved.getId(), saved.getOrderId(), saved.getStatus());
        return Optional.of(saved);
    }

    @Transactional
    void markCompleted(UUID paymentId, String providerTransactionId) {
        Payment payment = getById(paymentId);
        payment.complete(providerTransactionId);
        log.info("Payment completed, paymentId={}, providerTransactionId={}", paymentId, providerTransactionId);
    }

    @Transactional
    void markFailed(UUID paymentId) {
        Payment payment = getById(paymentId);
        payment.fail();
        log.info("Payment failed, paymentId={}", paymentId);
    }

    private Payment getById(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment not found: " + paymentId));
    }
}
