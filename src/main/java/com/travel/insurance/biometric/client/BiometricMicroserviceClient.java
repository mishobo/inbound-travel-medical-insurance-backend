package com.travel.insurance.biometric.client;

import com.travel.insurance.config.BiometricMicroserviceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class BiometricMicroserviceClient {

    private final BiometricMicroserviceProperties properties;
    private final RestClient.Builder restClientBuilder;

    public BiometricMicroserviceResponse createVerification(BiometricMicroserviceRequest request) {
        return restClientBuilder.build().post()
                .uri(baseUrl() + "/biometricsVerification")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new BiometricMicroserviceException(
                            "biometrics microservice trigger failed with status " + res.getStatusCode());
                })
                .body(BiometricMicroserviceResponse.class);
    }

    public HttpStatusCode resendCallback(String ekycRequestId) {
        return restClientBuilder.build().post()
                .uri(baseUrl() + "/api/verifications/{requestId}/resend", ekycRequestId)
                .exchange((req, res) -> {
                    res.close();
                    return res.getStatusCode();
                });
    }

    private String baseUrl() {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BiometricMicroserviceException(
                    "app.biometrics.microservice.base-url is not configured");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}