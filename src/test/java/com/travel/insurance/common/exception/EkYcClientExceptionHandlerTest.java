package com.travel.insurance.common.exception;

import com.travel.insurance.biometric.client.EkYcClientException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class EkYcClientExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        return req;
    }

    @Test
    void eKycClientErrorIsReportedAsBadGatewayWithJsonMessage() {
        ResponseEntity<ApiError> response = handler.handleEkYcClient(
                new EkYcClientException("identity provider unreachable"),
                request("/api/v1/biometries/callback"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo("eKYC service error: identity provider unreachable");
        assertThat(response.getBody().path())
                .isEqualTo("/api/v1/biometries/callback");
    }
}
