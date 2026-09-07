package com.nexus.payments.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Abstraction over the external payment provider so PaymentService depends
 * on a plain domain-shaped contract, not on RestClient or any HTTP type.
 */
public interface PaymentProviderClient {

    PaymentProviderResult processPayment(UUID orderId, UUID userId, BigDecimal amount, String currency);
}
