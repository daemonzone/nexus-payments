package com.nexus.payments.exception;

/**
 * The provider gave no definitive answer (connection failure, timeout, or
 * any non-business-error transport failure) - as opposed to a definitive
 * COMPLETED or FAILED response. Thrown so it propagates out of the
 * @RabbitListener and is handled by the existing RabbitMQ retry/DLQ
 * mechanism: reprocessing is safe because PaymentService.createPayment is
 * idempotent by orderId.
 */
public class PaymentProviderUnavailableException extends RuntimeException {

    public PaymentProviderUnavailableException(String message) {
        super(message);
    }

    public PaymentProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
