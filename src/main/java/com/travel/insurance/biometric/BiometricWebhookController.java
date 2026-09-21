package com.travel.insurance.biometric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.insurance.biometric.dto.BiometricCallbackPayload;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks/biometric-verification")
@RequiredArgsConstructor
public class BiometricWebhookController {

    private static final String TIMESTAMP_HEADER = "x-webhook-timestamp";
    private static final String SIGNATURE_HEADER = "x-webhook-signature";

    private final BiometricVerificationService biometricVerificationService;
    private final MicroserviceWebhookSignatureVerifier webhookSignatureVerifier;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody(required = false) String rawBody,
                                        @RequestHeader(value = TIMESTAMP_HEADER, required = false) String webhookTimestamp,
                                        @RequestHeader(value = SIGNATURE_HEADER, required = false) String webhookSignature,
                                        HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        log.info("Received biometric verification webhook callback from ip={}", remoteAddr);

        if (rawBody == null || rawBody.isBlank()) {
            log.warn("Biometric webhook received empty or null payload from ip={}", remoteAddr);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        if (!webhookSignatureVerifier.isValid(rawBody, webhookTimestamp, webhookSignature)) {
            log.warn("Biometric webhook signature verification failed from ip={}", remoteAddr);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        BiometricCallbackPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, BiometricCallbackPayload.class);
        } catch (Exception e) {
            log.warn("Biometric webhook payload could not be parsed from ip={}: {}", remoteAddr, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        try {
            biometricVerificationService.handleCallback(payload);
            log.info("Successfully processed biometric verification callback for requestId={}", payload.requestId());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to process biometric verification callback for requestId={}: {}",
                    payload.requestId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}