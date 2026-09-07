package com.nexus.payments.exception;

import com.nexus.payments.domain.PaymentStatus;

public class InvalidPaymentStateTransitionException extends RuntimeException {

    public InvalidPaymentStateTransitionException(PaymentStatus from, PaymentStatus to) {
        super("Cannot transition payment from " + from + " to " + to);
    }
}
