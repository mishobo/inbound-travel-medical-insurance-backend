package com.travel.insurance.policy.dto;

import com.travel.insurance.policy.PolicyStatus;
import com.travel.insurance.policy.PolicyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PolicyRequest(
        @NotBlank String policyNumber,
        @NotNull UUID insurerId,
        @NotNull PolicyType policyType,
        PolicyStatus status
) {
}
