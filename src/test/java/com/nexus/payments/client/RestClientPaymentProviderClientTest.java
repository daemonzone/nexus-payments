package com.nexus.payments.client;

import com.nexus.payments.exception.PaymentProviderUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestClientPaymentProviderClientTest {

    private static final String BASE_URL = "http://payment-provider";

    private MockRestServiceServer server;
    private RestClientPaymentProviderClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestClientPaymentProviderClient(builder.build());
    }

    @Test
    void processPayment_returnsSuccessResult_whenProviderRespondsWith200() {
        server.expect(requestTo(BASE_URL + "/payments"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"status\":\"COMPLETED\",\"providerTransactionId\":\"txn-123\"}",
                        MediaType.APPLICATION_JSON));

        PaymentProviderResult result = client.processPayment(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("49.99"), "EUR");

        assertThat(result.successful()).isTrue();
        assertThat(result.providerTransactionId()).isEqualTo("txn-123");
    }

    @Test
    void processPayment_returnsFailureResult_whenProviderRespondsWith500() {
        server.expect(requestTo(BASE_URL + "/payments"))
                .andRespond(withServerError());

        PaymentProviderResult result = client.processPayment(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("49.99"), "EUR");

        assertThat(result.successful()).isFalse();
        assertThat(result.providerTransactionId()).isNull();
    }

    @Test
    void processPayment_throwsUnavailable_whenConnectionFails() {
        server.expect(requestTo(BASE_URL + "/payments"))
                .andRespond(request -> {
                    throw new IOException("Connection refused");
                });

        assertThatThrownBy(() -> client.processPayment(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("49.99"), "EUR"))
                .isInstanceOf(PaymentProviderUnavailableException.class);
    }
}
