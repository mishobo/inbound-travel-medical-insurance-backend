package com.travel.insurance.biometric.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BiometricMicroserviceRequest(
        @JsonProperty("subject_id_number") String subjectIdNumber,
        @JsonProperty("subject_id_type") String subjectIdType,
        @JsonProperty("workstation_id") String workstationId,
        @JsonProperty("verification_request_id") String verificationRequestId
) {
}