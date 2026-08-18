package com.travel.insurance.policy.dto;

import com.travel.insurance.policy.PolicyStatus;
import com.travel.insurance.policy.PolicyType;

import java.time.Instant;
import java.util.UUID;

public record PolicyResponse(
        UUID id,
        String policyNumber,
        UUID insurerId,
        PolicyType policyType,
        PolicyStatus status,
        Instant createdDate,
        Instant updatedDate
) {
}
