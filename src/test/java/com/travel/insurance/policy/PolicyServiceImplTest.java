package com.travel.insurance.policy;

import com.travel.insurance.common.messaging.EventPublisher;
import com.travel.insurance.insurer.InsurerService;
import com.travel.insurance.policy.dto.PolicyRequest;
import com.travel.insurance.policy.dto.PolicyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyServiceImplTest {

    @Mock
    private PolicyRepository policyRepository;

    @Mock
    private InsurerService insurerService;

    @Mock
    private EventPublisher eventPublisher;

    private final PolicyMapper policyMapper = new PolicyMapper();

    private PolicyServiceImpl policyService;

    private final UUID insurerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        policyService = new PolicyServiceImpl(
                policyRepository, policyMapper, insurerService, eventPublisher);
    }

    private PolicyRequest requestWithType(PolicyType policyType, PolicyStatus status) {
        return new PolicyRequest("POL-001", insurerId, policyType, status);
    }

    @Test
    void createRejectsUnknownInsurer() {
        when(insurerService.exists(insurerId)).thenReturn(false);

        PolicyRequest request = requestWithType(PolicyType.IPMI_61_DAYS_TO_12_MONTHS, null);

        assertThatThrownBy(() -> policyService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(insurerId.toString());
        verify(policyRepository, never()).save(any());
    }

    @Test
    void createSavesPolicyWithPolicyType() {
        when(insurerService.exists(insurerId)).thenReturn(true);
        when(policyRepository.existsByPolicyNumber("POL-001")).thenReturn(false);
        when(policyRepository.save(any(Policy.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PolicyRequest request = requestWithType(PolicyType.SINGLE_ENTRY_UP_TO_30_DAYS, null);

        PolicyResponse response = policyService.create(request);

        assertThat(response.policyType()).isEqualTo(PolicyType.SINGLE_ENTRY_UP_TO_30_DAYS);
        verify(policyRepository).save(any(Policy.class));
    }

    @Test
    void createPublishesActivatedEventWhenStatusIsActive() {
        when(insurerService.exists(insurerId)).thenReturn(true);
        when(policyRepository.existsByPolicyNumber("POL-001")).thenReturn(false);
        when(policyRepository.save(any(Policy.class))).thenAnswer(invocation -> {
            Policy saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        PolicyRequest request = requestWithType(PolicyType.IPMI_61_DAYS_TO_12_MONTHS, PolicyStatus.ACTIVE);

        policyService.create(request);

        verify(eventPublisher).publish(eq("policy.activated"), any());
    }

    @Test
    void listByInsurerIdReturnsPoliciesBackedByThatInsurer() {
        Policy policy = policyMapper.toEntity(requestWithType(PolicyType.SINGLE_ENTRY_UP_TO_30_DAYS, null));
        policy.setId(UUID.randomUUID());
        when(policyRepository.findAllByInsurerId(insurerId)).thenReturn(List.of(policy));

        List<PolicyResponse> responses = policyService.listByInsurerId(insurerId);

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().insurerId()).isEqualTo(insurerId);
    }

    @Test
    void createDoesNotPublishActivatedEventWhenStatusIsDraft() {
        when(insurerService.exists(insurerId)).thenReturn(true);
        when(policyRepository.existsByPolicyNumber("POL-001")).thenReturn(false);
        when(policyRepository.save(any(Policy.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PolicyRequest request = requestWithType(PolicyType.IPMI_61_DAYS_TO_12_MONTHS, null);

        policyService.create(request);

        verify(eventPublisher, never()).publish(any(), any());
    }
}
