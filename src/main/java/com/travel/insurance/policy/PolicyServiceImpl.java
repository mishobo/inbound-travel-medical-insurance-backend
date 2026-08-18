package com.travel.insurance.policy;

import com.travel.insurance.common.exception.ResourceNotFoundException;
import com.travel.insurance.common.messaging.EventPublisher;
import com.travel.insurance.common.util.AuthenticatedUser;
import com.travel.insurance.common.util.SecurityUtils;
import com.travel.insurance.config.RabbitConfig;
import com.travel.insurance.insurer.InsurerService;
import com.travel.insurance.policy.dto.PolicyRequest;
import com.travel.insurance.policy.dto.PolicyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class PolicyServiceImpl implements PolicyService {

    private final PolicyRepository policyRepository;
    private final PolicyMapper policyMapper;
    private final InsurerService insurerService;
    private final EventPublisher eventPublisher;

    @Override
    public PolicyResponse create(PolicyRequest request) {
        validateRequest(request);
        if (policyRepository.existsByPolicyNumber(request.policyNumber())) {
            throw new IllegalStateException("Policy number already exists: " + request.policyNumber());
        }
        Policy policy = policyRepository.save(policyMapper.toEntity(request));
        publishIfActivated(null, policy);
        return policyMapper.toResponse(policy);
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyResponse getById(UUID id) {
        Policy policy = getEntityById(id);
        assertVisibleToCurrentUser(policy);
        return policyMapper.toResponse(policy);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PolicyResponse> list(Pageable pageable) {
        return findScoped(pageable).map(policyMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyResponse> listByInsurerId(UUID insurerId) {
        return policyRepository.findAllByInsurerId(insurerId).stream()
                .map(policyMapper::toResponse)
                .toList();
    }

    @Override
    public PolicyResponse update(UUID id, PolicyRequest request) {
        validateRequest(request);
        Policy policy = getEntityById(id);
        assertVisibleToCurrentUser(policy);
        PolicyStatus previousStatus = policy.getStatus();
        policyMapper.updateEntity(policy, request);
        Policy saved = policyRepository.save(policy);
        publishIfActivated(previousStatus, saved);
        return policyMapper.toResponse(saved);
    }

    @Override
    public void delete(UUID id) {
        Policy policy = getEntityById(id);
        assertVisibleToCurrentUser(policy);
        policyRepository.delete(policy);
    }

    @Override
    @Transactional(readOnly = true)
    public Policy getEntityById(UUID id) {
        return policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", id));
    }

    private void validateRequest(PolicyRequest request) {
        if (!insurerService.exists(request.insurerId())) {
            throw new IllegalArgumentException("Unknown insurer: " + request.insurerId());
        }
    }

    private Page<Policy> findScoped(Pageable pageable) {
        AuthenticatedUser user = SecurityUtils.currentUser().orElse(null);
        if (user == null) {
            return policyRepository.findAll(pageable);
        }
        if (user.roles().contains("INSURER_USER")) {
            return policyRepository.findAllByInsurerId(user.organizationId(), pageable);
        }
        return policyRepository.findAll(pageable);
    }

    private void assertVisibleToCurrentUser(Policy policy) {
        AuthenticatedUser user = SecurityUtils.currentUser().orElse(null);
        if (user == null) {
            return;
        }
        if (user.roles().contains("INSURER_USER") && !policy.getInsurerId().equals(user.organizationId())) {
            throw new AccessDeniedException("Policy belongs to another insurer");
        }
    }

    private void publishIfActivated(PolicyStatus previousStatus, Policy policy) {
        if (isActivating(previousStatus, policy)) {
            eventPublisher.publish(RabbitConfig.POLICY_ACTIVATED_KEY, Map.of(
                    "policyId", policy.getId().toString(),
                    "policyNumber", policy.getPolicyNumber(),
                    "insurerId", policy.getInsurerId().toString()));
        }
    }

    private boolean isActivating(PolicyStatus previousStatus, Policy policy) {
        return policy.getStatus() == PolicyStatus.ACTIVE && previousStatus != PolicyStatus.ACTIVE;
    }
}
