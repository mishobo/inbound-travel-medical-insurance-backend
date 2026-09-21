package com.travel.insurance.biometric;

import com.travel.insurance.config.BiometricMicroserviceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.security.MessageDigest;

/**
 * Verifies the HMAC-SHA256 signature the biometrics microservice attaches to
 * the forwarded eKYC callback. The signature is
 * {@code hex(HMAC-SHA256(webhookSecret, "<timestamp>.<rawBody>"))} carried in
 * the {@code x-webhook-signature} header, with the signing time in the
 * {@code x-webhook-timestamp} header (unix seconds) used to bound replay.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MicroserviceWebhookSignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final long ALLOWED_AGE_SECONDS = 300;

    private final BiometricMicroserviceProperties properties;

    public boolean isValid(String rawBody, String timestampHeader, String signatureHeader) {
        if (rawBody == null || rawBody.isBlank()
                || timestampHeader == null || signatureHeader == null) {
            return false;
        }

        String secret = properties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            log.error("app.biometrics.microservice.webhook-secret is not configured; rejecting webhook");
            return false;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > ALLOWED_AGE_SECONDS) {
            log.warn("Rejecting webhook with stale timestamp {}", timestampHeader);
            return false;
        }

        String expected = HexFormat.of().formatHex(
                hmacSha256(secret, timestampHeader + "." + rawBody));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}