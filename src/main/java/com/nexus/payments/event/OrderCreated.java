package com.nexus.payments.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of nexus-orders' OrderCreated contract. Deliberately not shared
 * via a common library: nexus-payments only needs the shape of the event,
 * and the message converter is configured to infer this type from the
 * listener method rather than trust the producer's (different) class name
 * in the message's __TypeId__ header. See RabbitConfig.
 */
public record OrderCreated(
        UUID eventId,
        UUID orderId,
        UUID userId,
        BigDecimal totalAmount,
        String currency,
        Instant occurredAt
) {
}
