package com.nexus.payments.service;

import com.nexus.payments.client.PaymentProviderClient;
import com.nexus.payments.client.PaymentProviderResult;
import com.nexus.payments.domain.Payment;
import com.nexus.payments.domain.PaymentStatus;
import com.nexus.payments.exception.PaymentProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Orchestrates OrderCreated processing: create/find the Payment, call the
 * external provider, then record its outcome. Deliberately not
 * @Transactional itself: the provider call is a slow, unreliable network
 * operation, and holding a database transaction (and its connection-pool
 * slot) open for its duration would let a slow or stuck provider starve the
 * pool for unrelated requests. Persistence happens in short, independent
 * transactions on {@link PaymentPersistenceService} instead: find-or-create
 * as PENDING, then (once the provider has answered) mark COMPLETED/FAILED.
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
        Payment payment = paymentPersistenceService.findOrCreatePending(orderId, userId, amount, currency);

        // A Payment that already reached a terminal state is fully handled -
        // this redelivery is a true duplicate (e.g. an ack lost after
        // success), a no-op. A Payment still PENDING is NOT a duplicate to
        // ignore, even though the row already exists: it means a previous
        // attempt never got a definitive answer from the provider (a
        // technical failure, not a decline), and this delivery is RabbitMQ
        // giving that attempt another chance - so it must retry the
        // provider call below, reusing the same row.
        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.info("Payment for orderId={} already reached a terminal state ({}), ignoring duplicate OrderCreated delivery",
                    orderId, payment.getStatus());
            return;
        }

        UUID paymentId = payment.getId();
        PaymentProviderResult result;
        try {
            result = paymentProviderClient.processPayment(orderId, userId, amount, currency);
        } catch (PaymentProviderUnavailableException ex) {
            // Leave the Payment PENDING: we don't know whether the provider
            // actually processed this request. Rethrow so the existing
            // RabbitMQ retry/DLQ mechanism handles the uncertainty -
            // reprocessing is safe because findOrCreatePending is idempotent
            // by orderId and will simply retry this same PENDING row.
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
