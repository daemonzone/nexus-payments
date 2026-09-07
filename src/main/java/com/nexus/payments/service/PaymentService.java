package com.nexus.payments.service;

import com.nexus.payments.client.PaymentProviderClient;
import com.nexus.payments.client.PaymentProviderResult;
import com.nexus.payments.domain.Payment;
import com.nexus.payments.exception.PaymentProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates OrderCreated processing: create/find the Payment, call the
 * external provider, then record its outcome. Deliberately not
 * @Transactional itself: the provider call is a slow, unreliable network
 * operation, and holding a database transaction (and its connection-pool
 * slot) open for its duration would let a slow or stuck provider starve the
 * pool for unrelated requests. Persistence happens in three short,
 * independent transactions on {@link PaymentPersistenceService} instead:
 * create-as-PENDING, then (once the provider has answered) mark
 * COMPLETED/FAILED.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentPersistenceService paymentPersistenceService;
    private final PaymentProviderClient paymentProviderClient;

    PaymentService(PaymentPersistenceService paymentPersistenceService, PaymentProviderClient paymentProviderClient) {
        this.paymentPersistenceService = paymentPersistenceService;
        this.paymentProviderClient = paymentProviderClient;
    }

    public void createPayment(UUID orderId, UUID userId, BigDecimal amount, String currency) {
        Optional<Payment> created = paymentPersistenceService.createPendingIfAbsent(orderId, userId, amount, currency);
        if (created.isEmpty()) {
            log.info("Payment already exists for orderId={}, ignoring duplicate OrderCreated delivery", orderId);
            return;
        }

        UUID paymentId = created.get().getId();
        PaymentProviderResult result;
        try {
            result = paymentProviderClient.processPayment(orderId, userId, amount, currency);
        } catch (PaymentProviderUnavailableException ex) {
            // Leave the Payment PENDING: we don't know whether the provider
            // actually processed this request. Rethrow so the existing
            // RabbitMQ retry/DLQ mechanism handles the uncertainty -
            // reprocessing is safe because createPendingIfAbsent is
            // idempotent by orderId.
            log.warn("Payment provider unavailable for orderId={}, leaving payment PENDING for retry: {}",
                    orderId, ex.getMessage());
            throw ex;
        }

        if (result.successful()) {
            paymentPersistenceService.markCompleted(paymentId, result.providerTransactionId());
        } else {
            // A definitive decline from the provider is a fully-handled
            // outcome, not a processing failure: no exception here, so the
            // message is acknowledged normally rather than retried/dead-lettered.
            paymentPersistenceService.markFailed(paymentId);
        }
    }
}
