package com.travel.insurance.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the biometric microservice that forwards eKYC VERIFIED
 * callbacks to this backend as HMAC-signed webhooks.
 *
 * <p>Verified against {@code BiometricMicroserviceClient} (getBaseUrl) and
 * {@code MicroserviceWebhookSignatureVerifier} (getWebhookSecret); must
 * match the micro's {@code MINETCALLBACKSECRET} on the signing side.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.biometrics.microservice")
public class BiometricMicroserviceProperties {

    private String baseUrl = "http://localhost:8080";
    private String webhookSecret;
}
