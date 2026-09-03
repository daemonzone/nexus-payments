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

    @Transactional
    public void createPayment(UUID orderId, UUID userId, BigDecimal amount, String currency) {
        Payment payment = new Payment(orderId, userId, amount, currency);
        Payment saved = paymentRepository.save(payment);
        log.info("Payment created, paymentId={}, orderId={}, status={}",
                saved.getId(), saved.getOrderId(), saved.getStatus());
    }
}
