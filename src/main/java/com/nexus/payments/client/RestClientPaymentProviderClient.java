package com.nexus.payments.client;

import com.nexus.payments.exception.PaymentProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class RestClientPaymentProviderClient implements PaymentProviderClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientPaymentProviderClient.class);

    private final RestClient paymentProviderRestClient;

    public RestClientPaymentProviderClient(RestClient paymentProviderRestClient) {
        this.paymentProviderRestClient = paymentProviderRestClient;
    }

    @Override
    public PaymentProviderResult processPayment(UUID orderId, UUID userId, BigDecimal amount, String currency) {
        try {
            ProviderResponse response = paymentProviderRestClient.post()
                    .uri("/payments")
                    .body(new ProviderRequest(orderId, userId, amount, currency))
                    .retrieve()
                    .body(ProviderResponse.class);

            if (response == null || response.providerTransactionId() == null) {
                throw new PaymentProviderUnavailableException("Payment provider returned an empty response");
            }
            log.info("Payment provider completed payment, orderId={}, providerTransactionId={}",
                    orderId, response.providerTransactionId());
            return PaymentProviderResult.success(response.providerTransactionId());
        } catch (HttpServerErrorException ex) {
            // The provider's own 500 for a declined payment is a definitive
            // business outcome (see nexus-payment-provider's contract), not a
            // transport failure - report it as a failed result, not as
            // unavailability.
            log.warn("Payment provider declined payment, orderId={}: {}", orderId, ex.getMessage());
            return PaymentProviderResult.failure();
        } catch (RestClientException ex) {
            log.warn("Payment provider unavailable, orderId={}: {}", orderId, ex.getMessage());
            throw new PaymentProviderUnavailableException("Payment provider unavailable", ex);
        }
    }

    private record ProviderRequest(UUID orderId, UUID userId, BigDecimal amount, String currency) {
    }

    private record ProviderResponse(String status, String providerTransactionId) {
    }
}
