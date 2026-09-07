package com.nexus.payments.client;

/**
 * A definitive outcome from the payment provider: either it completed the
 * payment (providerTransactionId set) or it explicitly declined it
 * (providerTransactionId null). Both are legitimate, terminal business
 * answers - unlike a transport failure, which the client reports as
 * {@link com.nexus.payments.exception.PaymentProviderUnavailableException}
 * rather than as a value here, since there is no answer to represent.
 */
public record PaymentProviderResult(boolean successful, String providerTransactionId) {

    public static PaymentProviderResult success(String providerTransactionId) {
        return new PaymentProviderResult(true, providerTransactionId);
    }

    public static PaymentProviderResult failure() {
        return new PaymentProviderResult(false, null);
    }
}
