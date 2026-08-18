package com.travel.insurance.policy.dto;

import com.travel.insurance.benefit.dto.BenefitResponse;
import com.travel.insurance.policy.PolicyStatus;
import com.travel.insurance.policy.PolicyType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PolicyDetailResponse(
        UUID id,
        String policyNumber,
        UUID insurerId,
        PolicyType policyType,
        PolicyStatus status,
        List<BenefitResponse> benefits,
        Instant createdDate,
        Instant updatedDate
) {

    public static PolicyDetailResponse of(PolicyResponse policy, List<BenefitResponse> benefits) {
        return new PolicyDetailResponse(
                policy.id(),
                policy.policyNumber(),
                policy.insurerId(),
                policy.policyType(),
                policy.status(),
                benefits,
                policy.createdDate(),
                policy.updatedDate());
    }
}
