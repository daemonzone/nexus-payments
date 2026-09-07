package com.nexus.payments.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Names and wires up the Retry/CircuitBreaker instances that protect the
 * payment-provider call. Their numeric settings live in application.yml
 * (resilience4j.retry/circuitbreaker.instances.paymentProvider) - this
 * class only names the instance, exposes it as an injectable bean (so
 * RestClientPaymentProviderClient depends on plain Retry/CircuitBreaker
 * objects rather than looking them up from a registry by string), and wires
 * the event logging described in the "resilience behavior" logging
 * requirement for this checkpoint.
 */
@Configuration
public class ResilienceConfig {

    static final String PAYMENT_PROVIDER = "paymentProvider";

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    @Bean
    Retry paymentProviderRetry(RetryRegistry retryRegistry) {
        Retry retry = retryRegistry.retry(PAYMENT_PROVIDER);
        retry.getEventPublisher()
                .onRetry(event -> log.info(
                        "Retrying payment provider call after transient failure, attempt={}, lastError={}",
                        event.getNumberOfRetryAttempts(), event.getLastThrowable().getMessage()))
                .onError(event -> log.warn(
                        "Payment provider call failed after {} attempts: {}",
                        event.getNumberOfRetryAttempts(), event.getLastThrowable().getMessage()));
        return retry;
    }

    @Bean
    CircuitBreaker paymentProviderCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(PAYMENT_PROVIDER);
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("Payment provider circuit breaker transitioned {} -> {}",
                        event.getStateTransition().getFromState(), event.getStateTransition().getToState()))
                .onCallNotPermitted(event -> log.warn(
                        "Payment provider circuit breaker is OPEN: call rejected without contacting the provider"))
                .onIgnoredError(event -> log.info(
                        "Payment provider call failed but is a client/request error, not counted against the circuit breaker: {}",
                        event.getThrowable().getMessage()));
        return circuitBreaker;
    }
}
