package com.travel.insurance.biometric.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BiometricMicroserviceResponse(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("relying_request_id") String relyingRequestId,
        @JsonProperty("embeded_token") String embededToken,
        @JsonProperty("embeded_expiry") String embededExpiry,
        @JsonProperty("request_url") String requestUrl
) {
}