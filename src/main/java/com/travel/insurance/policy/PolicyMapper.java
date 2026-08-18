package com.travel.insurance.policy;

import com.travel.insurance.policy.dto.PolicyRequest;
import com.travel.insurance.policy.dto.PolicyResponse;
import org.springframework.stereotype.Component;

@Component
public class PolicyMapper {

    public Policy toEntity(PolicyRequest request) {
        Policy policy = new Policy();
        updateEntity(policy, request);
        return policy;
    }

    public void updateEntity(Policy policy, PolicyRequest request) {
        policy.setPolicyNumber(request.policyNumber());
        policy.setInsurerId(request.insurerId());
        policy.setPolicyType(request.policyType());
        policy.setStatus(request.status() != null ? request.status() : PolicyStatus.DRAFT);
    }

    public PolicyResponse toResponse(Policy policy) {
        return new PolicyResponse(
                policy.getId(),
                policy.getPolicyNumber(),
                policy.getInsurerId(),
                policy.getPolicyType(),
                policy.getStatus(),
                policy.getCreatedDate(),
                policy.getUpdatedDate()
        );
    }
}
