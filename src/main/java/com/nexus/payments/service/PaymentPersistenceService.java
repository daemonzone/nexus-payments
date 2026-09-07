package com.nexus.payments.service;

import com.nexus.payments.domain.Payment;
import com.nexus.payments.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
     * Idempotent by orderId, but NOT by "a Payment row already exists":
     * a redelivered OrderCreated for an order whose Payment already reached
     * a terminal state (COMPLETED/FAILED) must be a no-op, but one whose
     * Payment is still PENDING - e.g. because the provider call failed on a
     * previous delivery attempt - must let the caller retry that call
     * against the SAME row, not silently swallow the redelivery. Returning
     * the existing row either way, and letting the caller branch on its
     * status, is what makes that distinction possible. This is a
     * check-then-act guard, not a race-proof lock - see the
     * uq_payments_order_id constraint, which remains the final integrity
     * guarantee if two deliveries are ever processed concurrently.
     */
    @Transactional
    Payment findOrCreatePending(UUID orderId, UUID userId, BigDecimal amount, String currency) {
        return paymentRepository.findByOrderId(orderId).orElseGet(() -> {
            Payment payment = new Payment(orderId, userId, amount, currency);
            Payment saved = paymentRepository.save(payment);
            log.info("Payment created, paymentId={}, orderId={}, status={}",
                    saved.getId(), saved.getOrderId(), saved.getStatus());
            return saved;
        });
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
