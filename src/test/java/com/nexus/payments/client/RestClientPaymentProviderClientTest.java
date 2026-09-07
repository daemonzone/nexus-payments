package com.nexus.payments.client;

import com.nexus.payments.exception.PaymentProviderUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Retry and CircuitBreaker instances are built by hand per test (near-zero
 * wait durations, tiny windows) rather than loaded from application.yml, so
 * this suite exercises the real Resilience4j logic without the real ~1s/2s/4s
 * production backoff or the real 10s open-state wait making it slow.
 */
class RestClientPaymentProviderClientTest {

    private static final String BASE_URL = "http://payment-provider";
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("49.99");
    private static final String CURRENCY = "EUR";

    // ============================= Retry =============================

    @Test
    void processPayment_retriesOnce_thenSucceeds_whenFirstAttemptFailsTransiently() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientPaymentProviderClient client = new RestClientPaymentProviderClient(
                builder.build(), fastRetry(4), permissiveCircuitBreaker());

        server.expect(requestTo(BASE_URL + "/payments")).andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());
        server.expect(requestTo(BASE_URL + "/payments"))
                .andRespond(withSuccess("{\"status\":\"COMPLETED\",\"providerTransactionId\":\"txn-123\"}",
                        MediaType.APPLICATION_JSON));

        PaymentProviderResult result = client.processPayment(ORDER_ID, USER_ID, AMOUNT, CURRENCY);

        assertThat(result.successful()).isTrue();
        assertThat(result.providerTransactionId()).isEqualTo("txn-123");
        server.verify();
    }

    @Test
    void processPayment_exhaustsRetries_thenThrowsUnavailable_whenProviderContinuouslyFails() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientPaymentProviderClient client = new RestClientPaymentProviderClient(
                builder.build(), fastRetry(4), permissiveCircuitBreaker());

        for (int i = 0; i < 4; i++) {
            server.expect(requestTo(BASE_URL + "/payments")).andRespond(withServerError());
        }

        assertThatThrownBy(() -> client.processPayment(ORDER_ID, USER_ID, AMOUNT, CURRENCY))
                .isInstanceOf(PaymentProviderUnavailableException.class);

        // Exactly the configured max-attempts (4) HTTP calls were made - not
        // fewer, and MockRestServiceServer would fail server.verify() below
        // if a 5th had been attempted.
        server.verify();
    }

    @Test
    void processPayment_retries_onConnectionFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientPaymentProviderClient client = new RestClientPaymentProviderClient(
                builder.build(), fastRetry(4), permissiveCircuitBreaker());

        server.expect(requestTo(BASE_URL + "/payments")).andRespond(request -> {
            throw new IOException("Connection refused");
        });
        server.expect(requestTo(BASE_URL + "/payments"))
                .andRespond(withSuccess("{\"status\":\"COMPLETED\",\"providerTransactionId\":\"txn-123\"}",
                        MediaType.APPLICATION_JSON));

        PaymentProviderResult result = client.processPayment(ORDER_ID, USER_ID, AMOUNT, CURRENCY);

        assertThat(result.successful()).isTrue();
        server.verify();
    }

    @Test
    void processPayment_doesNotRetry_onBusinessDecline() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientPaymentProviderClient client = new RestClientPaymentProviderClient(
                builder.build(), fastRetry(4), permissiveCircuitBreaker());

        server.expect(requestTo(BASE_URL + "/payments"))
                .andRespond(withSuccess("{\"status\":\"FAILED\",\"providerTransactionId\":null}",
                        MediaType.APPLICATION_JSON));

        PaymentProviderResult result = client.processPayment(ORDER_ID, USER_ID, AMOUNT, CURRENCY);

        assertThat(result.successful()).isFalse();
        // Only the single expectation above was registered: server.verify()
        // would fail if a retry attempt had made a second request.
        server.verify();
    }

    @Test
    void processPayment_doesNotRetry_on4xx() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientPaymentProviderClient client = new RestClientPaymentProviderClient(
                builder.build(), fastRetry(4), permissiveCircuitBreaker());

        server.expect(requestTo(BASE_URL + "/payments")).andRespond(withBadRequest());

        assertThatThrownBy(() -> client.processPayment(ORDER_ID, USER_ID, AMOUNT, CURRENCY))
                .isInstanceOf(PaymentProviderUnavailableException.class);

        server.verify();
    }

    // ================== Circuit Breaker failure classification ==================
    //
    // These prove the distinction between "should this be retried?" (Retry)
    // and "does this outcome count as evidence the provider is unhealthy?"
    // (CircuitBreaker). A 4xx answers the first question "no" (already
    // covered above) AND the second question "no" - it is our own malformed
    // request, not the provider misbehaving, so it must never be able to
    // trip the breaker and block valid requests from reaching a healthy
    // provider. A 5xx/transport failure answers "no" to the first only after
    // Retry is exhausted, and "yes" to the second.

    @Test
    void circuitBreaker_remainsClosed_whenProviderRepeatedlyReturns4xx() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CircuitBreaker circuitBreaker = smallCircuitBreaker();
        RestClientPaymentProviderClient client = new RestClientPaymentProviderClient(
                builder.build(), fastRetry(4), circuitBreaker);

        int attempts = 10; // well past the sliding window size (4)
        for (int i = 0; i < attempts; i++) {
            server.expect(requestTo(BASE_URL + "/payments")).andRespond(withBadRequest());
        }

        for (int i = 0; i < attempts; i++) {
            assertThatThrownBy(() -> client.processPayment(ORDER_ID, USER_ID, AMOUNT, CURRENCY))
                    .isInstanceOf(PaymentProviderUnavailableException.class);
        }

        // Exactly one HTTP call per processPayment() invocation - 4xx is not
        // retried - so `attempts` requests were made, not more:
        // server.verify() would fail below if a retry attempt had occurred.
        server.verify();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void circuitBreaker_ignoresLeading4xxCalls_soAValidRequestStillReachesAndCompletes() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CircuitBreaker circuitBreaker = smallCircuitBreaker();
        RestClientPaymentProviderClient client = new RestClientPaymentProviderClient(
                builder.build(), fastRetry(4), circuitBreaker);

        // Several malformed requests, as if a client-side bug were sending
        // them - enough to have opened the circuit if they were counted -
        // followed by one well-formed request. All expectations must be
        // registered before any request is made (MockRestServiceServer
        // rejects registering more once requests are underway).
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo(BASE_URL + "/payments")).andRespond(withBadRequest());
        }
        server.expect(requestTo(BASE_URL + "/payments"))
                .andRespond(withSuccess("{\"status\":\"COMPLETED\",\"providerTransactionId\":\"txn-789\"}",
                        MediaType.APPLICATION_JSON));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.processPayment(ORDER_ID, USER_ID, AMOUNT, CURRENCY))
                    .isInstanceOf(PaymentProviderUnavailableException.class);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // The valid request still reaches the (perfectly healthy) provider
        // and completes normally.
        PaymentProviderResult result = client.processPayment(ORDER_ID, USER_ID, AMOUNT, CURRENCY);

        assertThat(result.successful()).isTrue();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        server.verify();
    }

    @Test
    void circuitBreaker_remainsClosed_whenProviderRepeatedlyDeclines() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CircuitBreaker circuitBreaker = smallCircuitBreaker();
        RestClientPaymentProviderClient client = new RestClientPaymentProviderClient(
                builder.build(), fastRetry(4), circuitBreaker);

        int attempts = 6; // past the sliding window size (4)
        for (int i = 0; i < attempts; i++) {
            server.expect(requestTo(BASE_URL + "/payments"))
                    .andRespond(withSuccess("{\"status\":\"FAILED\",\"providerTransactionId\":null}",
                            MediaType.APPLICATION_JSON));
        }

        for (int i = 0; i < attempts; i++) {
            assertThat(client.processPayment(ORDER_ID, USER_ID, AMOUNT, CURRENCY).successful()).isFalse();
        }

        // A definitive decline is HTTP 200 - a normal response, not an
        // exception - so it was never even a candidate for circuit breaker
        // failure classification; this confirms that holds in practice too.
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        server.verify();
    }

    @Test
    void circuitBreaker_opensAfterConnectionFailures_onceRetryExhausted() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Retry disabled (1 attempt) so each processPayment() call maps to
        // exactly one HTTP request, keeping the circuit breaker's call count
        // easy to reason about - mirrors circuitBreaker_opensAfterFailureThreshold
        // below, but for a transport failure instead of a 5xx response.
        CircuitBreaker circuitBreaker = smallCircuitBreaker();
        RestClientPaymentProviderClient client = new RestClientPaymentProviderClient(
                builder.build(), fastRetry(1), circuitBreaker);

        for (int i = 0; i < 4; i++) {
            server.expect(requestTo(BASE_URL + "/payments")).andRespond(request -> {
                throw new IOException("Connection refused");
            });
        }

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.processPayment(ORDER_ID, USER_ID, AMOUNT, CURRENCY))
                    .isInstanceOf(PaymentProviderUnavailableException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        server.verify();
    }

    // ========================= Circuit Breaker =========================

    @Test
    void circuitBreaker_opensAfterFailureThreshold_andRejectsNextCallWithoutContactingProvider() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Retry disabled (1 attempt): each processPayment() call maps to
        // exactly one HTTP request, keeping the circuit breaker's call count
        // easy to reason about.
        CircuitBreaker circuitBreaker = smallCircuitBreaker();
        RestClientPaymentProviderClient client = new RestClientPaymentProviderClient(
                builder.build(), fastRetry(1), circuitBreaker);

        for (int i = 0; i < 4; i++) {
            server.expect(requestTo(BASE_URL + "/payments")).andRespond(withServerError());
        }

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.processPayment(ORDER_ID, USER_ID, AMOUNT, CURRENCY))
                    .isInstanceOf(PaymentProviderUnavailableException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // A 5th call must be rejected immediately: no 5th expectation was
        // registered above, so server.verify() below fails if the provider
        // were contacted again.
        assertThatThrownBy(() -> client.processPayment(ORDER_ID, USER_ID, AMOUNT, CURRENCY))
                .isInstanceOf(PaymentProviderUnavailableException.class);

        server.verify();
    }

    @Test
    void circuitBreaker_halfOpenProbeSucceeds_transitionsToClosed() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CircuitBreaker circuitBreaker = smallCircuitBreaker();
        RestClientPaymentProviderClient client = new RestClientPaymentProviderClient(
                builder.build(), fastRetry(1), circuitBreaker);

        // Force OPEN directly rather than driving it there with real failed
        // calls plus a real ~10s wait: the interesting behavior under test
        // here is what happens to HALF_OPEN probes, not how OPEN is reached
        // (already covered above).
        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();

        server.expect(requestTo(BASE_URL + "/payments"))
                .andRespond(withSuccess("{\"status\":\"COMPLETED\",\"providerTransactionId\":\"txn-123\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/payments"))
                .andRespond(withSuccess("{\"status\":\"COMPLETED\",\"providerTransactionId\":\"txn-456\"}",
                        MediaType.APPLICATION_JSON));

        // permitted-number-of-calls-in-half-open-state is 2 (see
        // smallCircuitBreaker()): both probes must succeed for it to decide.
        assertThat(client.processPayment(ORDER_ID, USER_ID, AMOUNT, CURRENCY).successful()).isTrue();
        assertThat(client.processPayment(ORDER_ID, USER_ID, AMOUNT, CURRENCY).successful()).isTrue();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        server.verify();
    }

    @Test
    void circuitBreaker_halfOpenProbeFails_returnsToOpen() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CircuitBreaker circuitBreaker = smallCircuitBreaker();
        RestClientPaymentProviderClient client = new RestClientPaymentProviderClient(
                builder.build(), fastRetry(1), circuitBreaker);

        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();

        server.expect(requestTo(BASE_URL + "/payments")).andRespond(withServerError());
        server.expect(requestTo(BASE_URL + "/payments")).andRespond(withServerError());

        assertThatThrownBy(() -> client.processPayment(ORDER_ID, USER_ID, AMOUNT, CURRENCY))
                .isInstanceOf(PaymentProviderUnavailableException.class);
        assertThatThrownBy(() -> client.processPayment(ORDER_ID, USER_ID, AMOUNT, CURRENCY))
                .isInstanceOf(PaymentProviderUnavailableException.class);

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        server.verify();
    }

    // ============================= Helpers =============================

    private Retry fastRetry(int maxAttempts) {
        return Retry.of("test-payment-provider-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(5))
                .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build());
    }

    /** Sized so it never opens within a single test focused on retry alone. */
    private CircuitBreaker permissiveCircuitBreaker() {
        return CircuitBreaker.of("test-payment-provider-" + UUID.randomUUID(), CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(100)
                .minimumNumberOfCalls(100)
                .failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .ignoreExceptions(HttpClientErrorException.class)
                .build());
    }

    /** Mirrors the shape of the production config (including ignoring 4xx), just small enough to drive by hand. */
    private CircuitBreaker smallCircuitBreaker() {
        return CircuitBreaker.of("test-payment-provider-" + UUID.randomUUID(), CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .ignoreExceptions(HttpClientErrorException.class)
                .build());
    }
}
