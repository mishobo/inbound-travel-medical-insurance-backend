package com.travel.insurance.visitor;

import com.travel.insurance.common.crypto.BlindIndexService;
import com.travel.insurance.insurer.Insurer;
import com.travel.insurance.insurer.InsurerRepository;
import com.travel.insurance.policy.Policy;
import com.travel.insurance.policy.PolicyService;
import com.travel.insurance.policy.PolicyType;
import com.travel.insurance.visitor.dto.VisitorRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for policy quota validation in VisitorServiceImpl.
 * Tests verify that visitors cannot be created when insurers have exhausted their policy quotas.
 */
@ExtendWith(MockitoExtension.class)
class VisitorQuotaValidationTest {

    @Mock
    private VisitorRepository visitorRepository;

    @Mock
    private PolicyService policyService;

    @Mock
    private InsurerRepository insurerRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private BlindIndexService blindIndexService;

    private final VisitorMapper visitorMapper = new VisitorMapper();

    private VisitorServiceImpl visitorService;

    private final UUID policyId = UUID.randomUUID();
    private final UUID insurerId = UUID.randomUUID();

    private VisitorRequest visitorRequest;

    @BeforeEach
    void setUp() {
        visitorService = new VisitorServiceImpl(
                visitorRepository, visitorMapper, policyService, insurerRepository, eventPublisher, blindIndexService);
        lenient().when(blindIndexService.hmac(anyString())).thenAnswer(invocation -> "HASH:" + invocation.getArgument(0));

        visitorRequest = new VisitorRequest(
                policyId,
                "Test Traveler",
                "P1234567",
                LocalDate.of(1990, 5, 12),
                Gender.MALE,
                "Kenya",
                "123 Test Street",
                "traveler@example.com",
                "+254700000000",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 15),
                MaritalStatus.SINGLE,
                "Tourism",
                "https://storage.example.com/photo.jpg",
                null,
                "Next of Kin",
                "+254711111111");
    }

    private Policy createPolicyWithInsurer(UUID insurerId) {
        Policy policy = new Policy();
        policy.setId(policyId);
        policy.setPolicyType(PolicyType.SINGLE_ENTRY_UP_TO_30_DAYS);
        policy.setInsurerId(insurerId);
        return policy;
    }

    private Insurer createInsurer(UUID id, String name, Long policyToken) {
        Insurer insurer = new Insurer();
        insurer.setId(id);
        insurer.setName(name);
        insurer.setPolicyToken(policyToken);
        return insurer;
    }

    @Test
    void createSucceedsWhenInsurerHasAvailablePolicies() {
        // Setup: Policy with insurer that has available policies
        Policy policy = createPolicyWithInsurer(insurerId);
        Insurer insurer = createInsurer(insurerId, "Minet Insurance", 100L);

        when(policyService.getEntityById(policyId)).thenReturn(policy);
        when(insurerRepository.findById(insurerId)).thenReturn(Optional.of(insurer));
        when(visitorRepository.existsByPassportNumberHash("HASH:P1234567")).thenReturn(false);
        when(visitorRepository.save(any(Visitor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act: Create visitor
        visitorService.create(visitorRequest);

        // Assert: Visitor was created
        verify(visitorRepository).save(any(Visitor.class));
        verify(eventPublisher).publishEvent(any(VisitorCreatedEvent.class));
    }

    @Test
    void createFailsWhenInsurerHasZeroPolicies() {
        // Setup: Policy with insurer that has zero policies
        Policy policy = createPolicyWithInsurer(insurerId);
        Insurer insurer = createInsurer(insurerId, "Minet Insurance", 0L);

        when(policyService.getEntityById(policyId)).thenReturn(policy);
        when(insurerRepository.findById(insurerId)).thenReturn(Optional.of(insurer));

        // Act & Assert: Creation fails with appropriate error
        assertThatThrownBy(() -> visitorService.create(visitorRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no available policies");
        verify(visitorRepository, never()).save(any(Visitor.class));
    }

    @Test
    void createFailsWhenInsurerHasNegativePolicies() {
        // Setup: Policy with insurer that has negative policies (edge case)
        Policy policy = createPolicyWithInsurer(insurerId);
        Insurer insurer = createInsurer(insurerId, "Minet Insurance", -5L);

        when(policyService.getEntityById(policyId)).thenReturn(policy);
        when(insurerRepository.findById(insurerId)).thenReturn(Optional.of(insurer));

        // Act & Assert: Creation fails
        assertThatThrownBy(() -> visitorService.create(visitorRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no available policies");
        verify(visitorRepository, never()).save(any(Visitor.class));
    }

    @Test
    void createFailsWhenInsurerHasNullPolicyToken() {
        // Setup: Policy with insurer that has null policy token
        Policy policy = createPolicyWithInsurer(insurerId);
        Insurer insurer = createInsurer(insurerId, "Minet Insurance", null);

        when(policyService.getEntityById(policyId)).thenReturn(policy);
        when(insurerRepository.findById(insurerId)).thenReturn(Optional.of(insurer));

        // Act & Assert: Creation fails
        assertThatThrownBy(() -> visitorService.create(visitorRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no available policies");
        verify(visitorRepository, never()).save(any(Visitor.class));
    }

    @Test
    void createSucceedsWhenLastPolicyAvailable() {
        // Setup: Policy with insurer that has exactly 1 policy left
        Policy policy = createPolicyWithInsurer(insurerId);
        Insurer insurer = createInsurer(insurerId, "Minet Insurance", 1L);

        when(policyService.getEntityById(policyId)).thenReturn(policy);
        when(insurerRepository.findById(insurerId)).thenReturn(Optional.of(insurer));
        when(visitorRepository.existsByPassportNumberHash("HASH:P1234567")).thenReturn(false);
        when(visitorRepository.save(any(Visitor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act: Create visitor
        visitorService.create(visitorRequest);

        // Assert: Visitor was created (will consume the last policy)
        verify(visitorRepository).save(any(Visitor.class));
        verify(eventPublisher).publishEvent(any(VisitorCreatedEvent.class));
    }

    @Test
    void createThrowsExceptionWithInsurerNameWhenQuotaExhausted() {
        // Setup: Policy with named insurer with exhausted quota
        Policy policy = createPolicyWithInsurer(insurerId);
        Insurer insurer = createInsurer(insurerId, "Minet Insurance Limited", 0L);

        when(policyService.getEntityById(policyId)).thenReturn(policy);
        when(insurerRepository.findById(insurerId)).thenReturn(Optional.of(insurer));

        // Act & Assert: Error message includes insurer name
        assertThatThrownBy(() -> visitorService.create(visitorRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Minet Insurance Limited")
                .hasMessageContaining("no available policies");
        verify(visitorRepository, never()).save(any(Visitor.class));
    }

    @Test
    void createValidatesQuotaBeforePassportUniquenessCheck() {
        // Setup: Insurer has no quota (should fail at quota check, not passport check)
        Policy policy = createPolicyWithInsurer(insurerId);
        Insurer insurer = createInsurer(insurerId, "Minet Insurance", 0L);

        when(policyService.getEntityById(policyId)).thenReturn(policy);
        when(insurerRepository.findById(insurerId)).thenReturn(Optional.of(insurer));
        // Note: We don't set up visitorRepository.existsByPassportNumber because
        // quota validation should fail first

        // Act & Assert: Quota validation happens before passport check
        assertThatThrownBy(() -> visitorService.create(visitorRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no available policies");
        verify(visitorRepository, never()).existsByPassportNumberHash(any());
    }

    @Test
    void createWithLargeAvailableQuota() {
        // Setup: Policy with very large available quota
        Policy policy = createPolicyWithInsurer(insurerId);
        Insurer insurer = createInsurer(insurerId, "Minet Insurance", 999999999L);

        when(policyService.getEntityById(policyId)).thenReturn(policy);
        when(insurerRepository.findById(insurerId)).thenReturn(Optional.of(insurer));
        when(visitorRepository.existsByPassportNumberHash("HASH:P1234567")).thenReturn(false);
        when(visitorRepository.save(any(Visitor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act: Create visitor
        visitorService.create(visitorRequest);

        // Assert: Visitor was created successfully
        verify(visitorRepository).save(any(Visitor.class));
        verify(eventPublisher).publishEvent(any(VisitorCreatedEvent.class));
    }
}
