package com.travel.insurance.biometric;

import com.travel.insurance.config.BiometricMicroserviceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;

class SecureHashVerifierTest {

    private static final String CLIENT_SECRET = "YXbpnafpVZP6B75ehYN2WhOtqST1w4b8";

    private MicroserviceWebhookSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        BiometricMicroserviceProperties properties = new BiometricMicroserviceProperties();
        properties.setWebhookSecret(CLIENT_SECRET);
        verifier = new MicroserviceWebhookSignatureVerifier(properties);
    }

    @Test
    void acceptsCallbackWithValidHmacSignature() {
        String raw = "{\"request_id\":\"529fd955-0000-0000-0000-000000000001\",\"status\":\"accepted\"}";
        long now = Instant.now().getEpochSecond();

        assertThat(verifier.isValid(raw, String.valueOf(now), hmac(raw, now))).isTrue();
    }

    @Test
    void rejectsCallbackWithTamperedSignature() {
        String raw = "{\"request_id\":\"529fd955-0000-0000-0000-000000000001\",\"status\":\"accepted\"}";
        long now = Instant.now().getEpochSecond();

        assertThat(verifier.isValid(raw, String.valueOf(now), "AAAAAAAA"))
                .isFalse();
    }

    @Test
    void rejectsCallbackWithMissingSignatureOrTimestamp() {
        String raw = "{\"request_id\":\"529fd955-0000-0000-0000-000000000001\",\"status\":\"accepted\"}";
        long now = Instant.now().getEpochSecond();

        assertThat(verifier.isValid(raw, null, hmac(raw, now))).isFalse();
        assertThat(verifier.isValid(raw, String.valueOf(now), null)).isFalse();
    }

    @Test
    void rejectsStaleTimestamp() {
        String raw = "{\"request_id\":\"529fd955-0000-0000-0000-000000000001\",\"status\":\"accepted\"}";
        long stale = Instant.now().getEpochSecond() - 3600;

        assertThat(verifier.isValid(raw, String.valueOf(stale), hmac(raw, stale))).isFalse();
    }

    private String hmac(String body, long timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(CLIENT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
