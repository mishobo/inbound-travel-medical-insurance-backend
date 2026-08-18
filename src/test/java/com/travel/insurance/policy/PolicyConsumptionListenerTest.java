package com.travel.insurance.policy;

import com.travel.insurance.insurer.Insurer;
import com.travel.insurance.insurer.InsurerRepository;
import com.travel.insurance.visitor.VisitorCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyConsumptionListenerTest {

    @Mock
    private PolicyRepository policyRepository;

    @Mock
    private InsurerRepository insurerRepository;

    private PolicyConsumptionListener listener;

    private final UUID policyId = UUID.randomUUID();
    private final UUID visitorId = UUID.randomUUID();
    private final UUID insurerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new PolicyConsumptionListener(policyRepository, insurerRepository);
    }

    @Test
    void onVisitorCreatedDecrementsInsurerPolicyToken() {
        // Setup: Create policy with one insurer
        Policy policy = new Policy();
        policy.setId(policyId);
        policy.setInsurerId(insurerId);

        Insurer insurer = new Insurer();
        insurer.setId(insurerId);
        insurer.setName("Minet Insurance");
        insurer.setPolicyToken(1000L);

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(insurerRepository.findById(insurerId)).thenReturn(Optional.of(insurer));
        when(insurerRepository.save(any(Insurer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act: Fire visitor created event
        VisitorCreatedEvent event = new VisitorCreatedEvent(visitorId, policyId);
        listener.onVisitorCreated(event);

        // Assert: Policy token was decremented
        ArgumentCaptor<Insurer> insurerCaptor = ArgumentCaptor.forClass(Insurer.class);
        verify(insurerRepository).save(insurerCaptor.capture());
        assertThat(insurerCaptor.getValue().getPolicyToken()).isEqualTo(999L);
    }

    @Test
    void onVisitorCreatedHandlesNullPolicyToken() {
        // Setup: Insurer with null policy token
        Policy policy = new Policy();
        policy.setId(policyId);
        policy.setInsurerId(insurerId);

        Insurer insurer = new Insurer();
        insurer.setId(insurerId);
        insurer.setName("Minet Insurance");
        insurer.setPolicyToken(null);

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(insurerRepository.findById(insurerId)).thenReturn(Optional.of(insurer));

        // Act: Fire visitor created event
        VisitorCreatedEvent event = new VisitorCreatedEvent(visitorId, policyId);
        listener.onVisitorCreated(event);

        // Assert: No save called (gracefully handled null case)
        verify(insurerRepository, never()).save(any(Insurer.class));
    }

    @Test
    void onVisitorCreatedHandlesZeroPolicyToken() {
        // Setup: Insurer with zero policy token
        Policy policy = new Policy();
        policy.setId(policyId);
        policy.setInsurerId(insurerId);

        Insurer insurer = new Insurer();
        insurer.setId(insurerId);
        insurer.setName("Minet Insurance");
        insurer.setPolicyToken(0L);

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(insurerRepository.findById(insurerId)).thenReturn(Optional.of(insurer));

        // Act: Fire visitor created event
        VisitorCreatedEvent event = new VisitorCreatedEvent(visitorId, policyId);
        listener.onVisitorCreated(event);

        // Assert: No save called (gracefully handled zero case)
        verify(insurerRepository, never()).save(any(Insurer.class));
    }

    @Test
    void onVisitorCreatedThrowsExceptionWhenPolicyNotFound() {
        // Setup: Policy doesn't exist
        when(policyRepository.findById(policyId)).thenReturn(Optional.empty());

        // Act & Assert: Exception thrown
        VisitorCreatedEvent event = new VisitorCreatedEvent(visitorId, policyId);
        assertThatThrownBy(() -> listener.onVisitorCreated(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Policy not found");
        verify(insurerRepository, never()).save(any(Insurer.class));
    }

    @Test
    void onVisitorCreatedThrowsExceptionWhenInsurerNotFound() {
        // Setup: Policy exists but insurer doesn't
        Policy policy = new Policy();
        policy.setId(policyId);
        policy.setInsurerId(insurerId);

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(insurerRepository.findById(insurerId)).thenReturn(Optional.empty());

        // Act & Assert: Exception thrown
        VisitorCreatedEvent event = new VisitorCreatedEvent(visitorId, policyId);
        assertThatThrownBy(() -> listener.onVisitorCreated(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insurer not found");
    }

    @Test
    void onVisitorCreatedDecrementsTokenFromHighValue() {
        // Setup: High initial token value
        Policy policy = new Policy();
        policy.setId(policyId);
        policy.setInsurerId(insurerId);

        Insurer insurer = new Insurer();
        insurer.setId(insurerId);
        insurer.setName("Minet Insurance");
        insurer.setPolicyToken(999999L);

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(insurerRepository.findById(insurerId)).thenReturn(Optional.of(insurer));
        when(insurerRepository.save(any(Insurer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act: Fire visitor created event
        VisitorCreatedEvent event = new VisitorCreatedEvent(visitorId, policyId);
        listener.onVisitorCreated(event);

        // Assert: Token properly decremented
        ArgumentCaptor<Insurer> insurerCaptor = ArgumentCaptor.forClass(Insurer.class);
        verify(insurerRepository).save(insurerCaptor.capture());
        assertThat(insurerCaptor.getValue().getPolicyToken()).isEqualTo(999998L);
    }
}
