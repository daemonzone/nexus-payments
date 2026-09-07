package com.nexus.payments.client;

import com.nexus.payments.exception.PaymentProviderUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Composition order is CircuitBreaker(Retry(HTTP call)) - built by applying
 * .withRetry(...) first and .withCircuitBreaker(...) last, since each
 * Decorators call wraps the *previous* result, making the last-applied
 * decorator the outermost one. This matters: Retry, the innermost decorator,
 * may perform several HTTP attempts for a single processPayment() call, but
 * the CircuitBreaker only ever sees ONE outcome - success or final failure -
 * per call, not one per HTTP attempt. That is what makes "the circuit breaker
 * counts business requests, not retry attempts" true. Putting CircuitBreaker
 * innermost would make it record (and potentially reject) individual retry
 * attempts instead, which is not the intended semantics.
 * <p>
 * Retry and CircuitBreaker answer different questions and are configured
 * independently (see application.yml): Retry asks "should I try this HTTP
 * call again?" (yes for connection failures/timeouts/5xx, no for 4xx or a
 * definitive decline); CircuitBreaker asks "does this outcome count as
 * evidence the provider is unhealthy?" (yes for an exhausted Retry sequence,
 * no for a 4xx - configured as an ignored exception - since a 4xx means OUR
 * request was rejected, not that the provider is failing).
 */
@Component
public class RestClientPaymentProviderClient implements PaymentProviderClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientPaymentProviderClient.class);

    private final RestClient paymentProviderRestClient;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public RestClientPaymentProviderClient(RestClient paymentProviderRestClient, Retry retry, CircuitBreaker circuitBreaker) {
        this.paymentProviderRestClient = paymentProviderRestClient;
        this.retry = retry;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public PaymentProviderResult processPayment(UUID orderId, UUID userId, BigDecimal amount, String currency) {
        Supplier<ProviderResponse> call = () -> callProvider(orderId, userId, amount, currency);
        Supplier<ProviderResponse> resilientCall = Decorators.ofSupplier(call)
                .withRetry(retry)
                .withCircuitBreaker(circuitBreaker)
                .decorate();

        ProviderResponse response;
        try {
            response = resilientCall.get();
        } catch (CallNotPermittedException ex) {
            // The circuit is OPEN: no HTTP request was attempted at all.
            log.warn("Payment provider call skipped for orderId={}: circuit breaker is open", orderId);
            throw new PaymentProviderUnavailableException("Payment provider circuit breaker is open", ex);
        } catch (RuntimeException ex) {
            // Retry exhausted its attempts, or the failure wasn't retryable
            // (e.g. a 4xx) and propagated on the first attempt. Either way
            // this is a request PaymentService could not get a definitive
            // answer for - a 4xx is not counted as circuit breaker evidence
            // (see application.yml ignore-exceptions), but it still isn't a
            // definitive answer, so it is wrapped the same way here.
            log.warn("Payment provider unavailable for orderId={}: {}", orderId, ex.getMessage());
            throw new PaymentProviderUnavailableException("Payment provider unavailable", ex);
        }

        return interpret(orderId, response);
    }

    private ProviderResponse callProvider(UUID orderId, UUID userId, BigDecimal amount, String currency) {
        log.debug("Calling payment provider, orderId={}", orderId);
        return paymentProviderRestClient.post()
                .uri("/payments")
                .body(new ProviderRequest(orderId, userId, amount, currency))
                .retrieve()
                .body(ProviderResponse.class);
    }

    private PaymentProviderResult interpret(UUID orderId, ProviderResponse response) {
        if (response == null || response.status() == null) {
            throw new PaymentProviderUnavailableException("Payment provider returned an empty response");
        }
        if ("COMPLETED".equals(response.status())) {
            log.info("Payment provider completed payment, orderId={}, providerTransactionId={}",
                    orderId, response.providerTransactionId());
            return PaymentProviderResult.success(response.providerTransactionId());
        }
        // A definitive decline (HTTP 200, status=FAILED - see
        // nexus-payment-provider's PaymentProviderService) is a normal
        // response, not an exception, so it never reaches the retry/circuit
        // breaker error paths above.
        log.info("Payment provider declined payment, orderId={}", orderId);
        return PaymentProviderResult.failure();
    }

    private record ProviderRequest(UUID orderId, UUID userId, BigDecimal amount, String currency) {
    }

    private record ProviderResponse(String status, String providerTransactionId) {
    }
}
