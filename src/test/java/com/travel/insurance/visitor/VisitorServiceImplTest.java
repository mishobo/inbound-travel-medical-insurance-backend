package com.travel.insurance.visitor;

import com.travel.insurance.common.crypto.BlindIndexService;
import com.travel.insurance.common.exception.ResourceNotFoundException;
import com.travel.insurance.insurer.Insurer;
import com.travel.insurance.insurer.InsurerRepository;
import com.travel.insurance.policy.Policy;
import com.travel.insurance.policy.PolicyService;
import com.travel.insurance.policy.PolicyType;
import com.travel.insurance.visitor.dto.VisitorRequest;
import com.travel.insurance.visitor.dto.VisitorResponse;
import com.travel.insurance.visitor.dto.VisitorStatusUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisitorServiceImplTest {

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

    private UUID policyId;
    private UUID insurerId;
    private VisitorRequest request;

    private static String hashOf(String passportNumber) {
        return "HASH:" + passportNumber;
    }

    @BeforeEach
    void setUp() {
        visitorService = new VisitorServiceImpl(
                visitorRepository, visitorMapper, policyService, insurerRepository, eventPublisher, blindIndexService);
        lenient().when(blindIndexService.hmac(anyString()))
                .thenAnswer(invocation -> hashOf(invocation.getArgument(0)));
        policyId = UUID.randomUUID();
        insurerId = UUID.randomUUID();
        Insurer insurer = new Insurer();
        insurer.setId(insurerId);
        insurer.setName("Minet Insurance");
        insurer.setPolicyToken(1000L);
        lenient().when(insurerRepository.findById(insurerId)).thenReturn(Optional.of(insurer));
        request = new VisitorRequest(
                policyId,
                "Jane Traveler",
                "P1234567",
                LocalDate.of(1990, 5, 12),
                Gender.FEMALE,
                "Germany",
                "12 Example Street, Berlin",
                "jane.traveler@example.com",
                "+254700000000",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 11, 1),
                MaritalStatus.SINGLE,
                "Tourism",
                "https://storage.example.com/photos/jane.jpg",
                null,
                "John Traveler",
                "+254711111111");
    }

    private Policy policyOfType(PolicyType policyType) {
        Policy policy = new Policy();
        policy.setPolicyType(policyType);
        policy.setInsurerId(insurerId);
        return policy;
    }

    @Test
    void createSavesWhenPolicyFreeAndPassportUnique() {
        when(policyService.getEntityById(policyId)).thenReturn(policyOfType(PolicyType.IPMI_61_DAYS_TO_12_MONTHS));
        when(visitorRepository.existsByPassportNumberHash(hashOf("P1234567"))).thenReturn(false);
        when(visitorRepository.save(any(Visitor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        VisitorResponse response = visitorService.create(request);

        assertThat(response.fullName()).isEqualTo("Jane Traveler");
        assertThat(response.policyId()).isEqualTo(policyId);
        assertThat(response.visitorStatus()).isEqualTo(VisitorStatus.ACTIVE);
        verify(visitorRepository).save(any(Visitor.class));
        verify(eventPublisher).publishEvent(any(VisitorCreatedEvent.class));
    }

    @Test
    void createAllowsSecondVisitorOnSamePolicy() {
        when(policyService.getEntityById(policyId)).thenReturn(policyOfType(PolicyType.IPMI_61_DAYS_TO_12_MONTHS));
        when(visitorRepository.existsByPassportNumberHash(hashOf("P7654321"))).thenReturn(false);
        when(visitorRepository.save(any(Visitor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        VisitorRequest secondVisitor = new VisitorRequest(
                policyId,
                "John Traveler",
                "P7654321",
                LocalDate.of(1988, 2, 3),
                Gender.MALE,
                "Germany",
                "12 Example Street, Berlin",
                "john.traveler@example.com",
                "+254722222222",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 11, 1),
                MaritalStatus.MARRIED,
                "Business",
                "https://storage.example.com/photos/john.jpg",
                "Diabetes, requires insulin",
                "Jane Traveler",
                "+254700000000");

        VisitorResponse response = visitorService.create(secondVisitor);

        assertThat(response.policyId()).isEqualTo(policyId);
        verify(visitorRepository).save(any(Visitor.class));
    }

    @Test
    void createRejectsDuplicatePassportNumber() {
        when(policyService.getEntityById(policyId)).thenReturn(policyOfType(PolicyType.IPMI_61_DAYS_TO_12_MONTHS));
        when(visitorRepository.existsByPassportNumberHash(hashOf("P1234567"))).thenReturn(true);

        assertThatThrownBy(() -> visitorService.create(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("P1234567");
        verify(visitorRepository, never()).save(any());
    }

    private VisitorRequest requestWithTravelPeriod(LocalDate dateIn, LocalDate dateOut) {
        return new VisitorRequest(
                policyId, "Jane Traveler", "P1234567", LocalDate.of(1990, 5, 12), Gender.FEMALE,
                "Germany", "12 Example Street, Berlin", "jane.traveler@example.com", "+254700000000",
                dateIn, dateOut, MaritalStatus.SINGLE, "Tourism",
                "https://storage.example.com/photos/jane.jpg", null, "John Traveler", "+254711111111");
    }

    @Test
    void createRejectsDateOutBeforeDateIn() {
        when(policyService.getEntityById(policyId)).thenReturn(policyOfType(PolicyType.IPMI_61_DAYS_TO_12_MONTHS));

        VisitorRequest reversed = requestWithTravelPeriod(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 1));

        assertThatThrownBy(() -> visitorService.create(reversed))
                .isInstanceOf(IllegalArgumentException.class);
        verify(visitorRepository, never()).save(any());
    }

    @Test
    void createAcceptsShortestBoundaryForSingleEntryUpTo30Days() {
        when(policyService.getEntityById(policyId)).thenReturn(policyOfType(PolicyType.SINGLE_ENTRY_UP_TO_30_DAYS));
        when(visitorRepository.existsByPassportNumberHash(hashOf("P1234567"))).thenReturn(false);
        when(visitorRepository.save(any(Visitor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        VisitorRequest thirtyDays = requestWithTravelPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 30));

        VisitorResponse response = visitorService.create(thirtyDays);

        assertThat(response.dateIn()).isEqualTo(LocalDate.of(2026, 1, 1));
        verify(visitorRepository).save(any(Visitor.class));
    }

    @Test
    void createRejectsTravelPeriodExceedingSingleEntryUpTo30Days() {
        when(policyService.getEntityById(policyId)).thenReturn(policyOfType(PolicyType.SINGLE_ENTRY_UP_TO_30_DAYS));

        VisitorRequest thirtyOneDays = requestWithTravelPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertThatThrownBy(() -> visitorService.create(thirtyOneDays))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SINGLE_ENTRY_UP_TO_30_DAYS");
        verify(visitorRepository, never()).save(any());
    }

    @Test
    void createRejectsTravelPeriodBelowSingleEntry31To60DaysMinimum() {
        when(policyService.getEntityById(policyId)).thenReturn(policyOfType(PolicyType.SINGLE_ENTRY_31_TO_60_DAYS));

        VisitorRequest fifteenDays = requestWithTravelPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 15));

        assertThatThrownBy(() -> visitorService.create(fifteenDays))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SINGLE_ENTRY_31_TO_60_DAYS");
        verify(visitorRepository, never()).save(any());
    }

    @Test
    void createRejectsTravelPeriodBelowIpmiMinimum() {
        when(policyService.getEntityById(policyId)).thenReturn(policyOfType(PolicyType.IPMI_61_DAYS_TO_12_MONTHS));

        VisitorRequest thirtyTwoDays = requestWithTravelPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));

        assertThatThrownBy(() -> visitorService.create(thirtyTwoDays))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IPMI_61_DAYS_TO_12_MONTHS");
        verify(visitorRepository, never()).save(any());
    }

    @Test
    void getByPassportNumberReturnsVisitorKyc() {
        Visitor existing = visitorMapper.toEntity(request);
        when(visitorRepository.findByPassportNumberHash(hashOf("P1234567")))
                .thenReturn(Optional.of(existing));

        VisitorResponse response = visitorService.getByPassportNumber("P1234567");

        assertThat(response.passportNumber()).isEqualTo("P1234567");
        assertThat(response.fullName()).isEqualTo("Jane Traveler");
        assertThat(response.policyId()).isEqualTo(policyId);
    }

    @Test
    void getByPassportNumberThrowsWhenUnknown() {
        when(visitorRepository.findByPassportNumberHash(hashOf("UNKNOWN")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> visitorService.getByPassportNumber("UNKNOWN"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    void updateRejectsPassportUsedByAnotherVisitor() {
        UUID id = UUID.randomUUID();
        Visitor existing = visitorMapper.toEntity(request);
        when(visitorRepository.findById(id)).thenReturn(Optional.of(existing));
        when(policyService.getEntityById(policyId)).thenReturn(policyOfType(PolicyType.IPMI_61_DAYS_TO_12_MONTHS));
        when(visitorRepository.existsByPassportNumberHashAndIdNot(hashOf("P1234567"), id)).thenReturn(true);

        assertThatThrownBy(() -> visitorService.update(id, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("P1234567");
        verify(visitorRepository, never()).save(any());
    }

    @Test
    void updateAllowsKeepingOwnPassportNumber() {
        UUID id = UUID.randomUUID();
        Visitor existing = visitorMapper.toEntity(request);
        when(visitorRepository.findById(id)).thenReturn(Optional.of(existing));
        when(policyService.getEntityById(policyId)).thenReturn(policyOfType(PolicyType.IPMI_61_DAYS_TO_12_MONTHS));
        when(visitorRepository.existsByPassportNumberHashAndIdNot(hashOf("P1234567"), id)).thenReturn(false);

        VisitorResponse response = visitorService.update(id, request);

        assertThat(response.passportNumber()).isEqualTo("P1234567");
    }

    @Test
    void updateVisitorStatusAppliesAllowedTransitionAndPublishesEvent() {
        UUID id = UUID.randomUUID();
        Visitor existing = visitorMapper.toEntity(request);
        existing.setVisitorStatus(VisitorStatus.PENDING);
        when(visitorRepository.findById(id)).thenReturn(Optional.of(existing));

        visitorService.updateVisitorStatus(id, new VisitorStatusUpdate(VisitorStatus.ACTIVE));

        assertThat(existing.getVisitorStatus()).isEqualTo(VisitorStatus.ACTIVE);
        ArgumentCaptor<VisitorStatusChangedEvent> captor =
                ArgumentCaptor.forClass(VisitorStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().newStatus()).isEqualTo(VisitorStatus.ACTIVE);
    }

    @Test
    void updateVisitorStatusRejectsInvalidTransition() {
        UUID id = UUID.randomUUID();
        Visitor existing = visitorMapper.toEntity(request);
        existing.setVisitorStatus(VisitorStatus.DEACTIVATED);
        when(visitorRepository.findById(id)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> visitorService.updateVisitorStatus(
                id, new VisitorStatusUpdate(VisitorStatus.ACTIVE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEACTIVATED")
                .hasMessageContaining("ACTIVE");
        assertThat(existing.getVisitorStatus()).isEqualTo(VisitorStatus.DEACTIVATED);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void updateVisitorStatusRejectsSameStatus() {
        UUID id = UUID.randomUUID();
        Visitor existing = visitorMapper.toEntity(request);
        when(visitorRepository.findById(id)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> visitorService.updateVisitorStatus(
                id, new VisitorStatusUpdate(VisitorStatus.PENDING)))
                .isInstanceOf(IllegalStateException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void updateVisitorStatusByPassportNumberAppliesAllowedTransitionAndPublishesEvent() {
        Visitor existing = visitorMapper.toEntity(request);
        existing.setVisitorStatus(VisitorStatus.PENDING);
        when(visitorRepository.findByPassportNumberHash(hashOf("P1234567")))
                .thenReturn(Optional.of(existing));

        visitorService.updateVisitorStatusByPassportNumber(
                "P1234567", new VisitorStatusUpdate(VisitorStatus.ACTIVE));

        assertThat(existing.getVisitorStatus()).isEqualTo(VisitorStatus.ACTIVE);
        ArgumentCaptor<VisitorStatusChangedEvent> captor =
                ArgumentCaptor.forClass(VisitorStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().newStatus()).isEqualTo(VisitorStatus.ACTIVE);
    }

    @Test
    void updateVisitorStatusByPassportNumberRejectsInvalidTransition() {
        Visitor existing = visitorMapper.toEntity(request);
        existing.setVisitorStatus(VisitorStatus.DEACTIVATED);
        when(visitorRepository.findByPassportNumberHash(hashOf("P1234567")))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> visitorService.updateVisitorStatusByPassportNumber(
                "P1234567", new VisitorStatusUpdate(VisitorStatus.ACTIVE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEACTIVATED")
                .hasMessageContaining("ACTIVE");
        assertThat(existing.getVisitorStatus()).isEqualTo(VisitorStatus.DEACTIVATED);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void updateVisitorStatusByPassportNumberThrowsWhenVisitorUnknown() {
        when(visitorRepository.findByPassportNumberHash(hashOf("UNKNOWN")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> visitorService.updateVisitorStatusByPassportNumber(
                "UNKNOWN", new VisitorStatusUpdate(VisitorStatus.ACTIVE)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void updateVisitorStatusThrowsWhenVisitorUnknown() {
        UUID id = UUID.randomUUID();
        when(visitorRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> visitorService.updateVisitorStatus(
                id, new VisitorStatusUpdate(VisitorStatus.ACTIVE)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }
}
