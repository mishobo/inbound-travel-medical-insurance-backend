package com.travel.insurance.biometric.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BiometricCallbackPayload(
        @JsonProperty("request_id") String requestId,
        String status,
        String result,
        @JsonProperty("status_code") String statusCode,
        @JsonProperty("remaining_attempts") Integer remainingAttempts
) {
}