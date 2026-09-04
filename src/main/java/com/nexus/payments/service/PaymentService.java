package com.nexus.payments.service;

import com.nexus.payments.domain.Payment;
import com.nexus.payments.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /**
     * Idempotent by orderId: a redelivered OrderCreated for an order that
     * already has a Payment is treated as already handled and is a no-op.
     * This is a check-then-act guard, not a race-proof lock - see the
     * uq_payments_order_id constraint, which remains the final integrity
     * guarantee if two deliveries are ever processed concurrently.
     */
    @Transactional
    public void createPayment(UUID orderId, UUID userId, BigDecimal amount, String currency) {
        if (paymentRepository.findByOrderId(orderId).isPresent()) {
            log.info("Payment already exists for orderId={}, ignoring duplicate OrderCreated delivery", orderId);
            return;
        }

        Payment payment = new Payment(orderId, userId, amount, currency);
        Payment saved = paymentRepository.save(payment);
        log.info("Payment created, paymentId={}, orderId={}, status={}",
                saved.getId(), saved.getOrderId(), saved.getStatus());
    }
}
