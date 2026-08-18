package com.travel.insurance.claim;

import com.travel.insurance.benefit.BenefitService;
import com.travel.insurance.claim.dto.AttachInvoiceRequest;
import com.travel.insurance.claim.dto.ClaimDecisionRequest;
import com.travel.insurance.claim.dto.ClaimRequest;
import com.travel.insurance.claim.dto.ClaimResponse;
import com.travel.insurance.common.exception.ResourceNotFoundException;
import com.travel.insurance.common.messaging.EventPublisher;
import com.travel.insurance.common.service.CurrencyConversionService;
import com.travel.insurance.icd11.Icd11CodeService;
import com.travel.insurance.icd11.dto.Icd11CodeResponse;
import com.travel.insurance.insurer.InsurerService;
import com.travel.insurance.insurer.dto.InsurerResponse;
import com.travel.insurance.invoice.Invoice;
import com.travel.insurance.invoice.InvoiceService;
import com.travel.insurance.invoice.dto.InvoiceResponse;
import com.travel.insurance.policy.Policy;
import com.travel.insurance.policy.PolicyService;
import com.travel.insurance.procedure.ProcedureService;
import com.travel.insurance.procedure.dto.ProcedureResponse;
import com.travel.insurance.visitor.Visitor;
import com.travel.insurance.visitor.VisitorService;
import com.travel.insurance.visitor.dto.VisitorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimServiceImplTest {

    @Mock
    private ClaimRepository claimRepository;

    @Mock
    private PolicyService policyService;

    @Mock
    private BenefitService benefitService;

    @Mock
    private VisitorService visitorService;

    @Mock
    private InsurerService insurerService;

    @Mock
    private InvoiceService invoiceService;

    @Mock
    private Icd11CodeService icd11CodeService;

    @Mock
    private ProcedureService procedureService;

    @Mock
    private CurrencyConversionService currencyConversionService;

    @Mock
    private EventPublisher eventPublisher;

    private final ClaimMapper claimMapper = new ClaimMapper();

    private ClaimServiceImpl claimService;

    private UUID policyId;
    private UUID benefitId;
    private UUID visitorId;
    private UUID insurerId;
    private UUID invoiceId;
    private UUID documentId;
    private UUID diagnosisId1;
    private UUID diagnosisId2;
    private UUID procedureId1;

    private static final BigDecimal KES_TO_USD = new BigDecimal("0.0077");

    @BeforeEach
    void setUp() {
        claimService = new ClaimServiceImpl(
                claimRepository, claimMapper, policyService, benefitService,
                visitorService, insurerService, invoiceService,
                icd11CodeService, procedureService,
                currencyConversionService, eventPublisher);
        policyId = UUID.randomUUID();
        benefitId = UUID.randomUUID();
        visitorId = UUID.randomUUID();
        insurerId = UUID.randomUUID();
        invoiceId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        diagnosisId1 = UUID.randomUUID();
        diagnosisId2 = UUID.randomUUID();
        procedureId1 = UUID.randomUUID();
    }

    private Policy policyCoveredBy(UUID insurerId) {
        Policy policy = new Policy();
        policy.setId(policyId);
        policy.setInsurerId(insurerId);
        return policy;
    }

    private ClaimRequest baseRequest() {
        return new ClaimRequest(
                policyId, benefitId, null, null, null,
                new BigDecimal("50000.00"), null, null, null, null, null, null);
    }

    private ClaimRequest fullRequest() {
        return new ClaimRequest(
                policyId, benefitId, null, null, visitorId,
                new BigDecimal("50000.00"), "Hospital stay",
                "Paracetamol 500mg twice daily",
                Set.of(diagnosisId1, diagnosisId2),
                Set.of(procedureId1),
                Set.of(invoiceId),
                Set.of(documentId));
    }

    private Visitor visitorOnPolicy() {
        Visitor visitor = new Visitor();
        visitor.setId(visitorId);
        visitor.setPolicyId(policyId);
        return visitor;
    }

    private VisitorResponse visitorResponse() {
        return new VisitorResponse(visitorId, policyId, "Jane Traveler", "P1234567",
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, Instant.now(), Instant.now());
    }

    private InsurerResponse insurerResponse() {
        return new InsurerResponse(insurerId, "Jubilee Insurance", null, null, null, null,
                null, null, Instant.now(), Instant.now());
    }

    private InvoiceResponse invoiceResponse() {
        return new InvoiceResponse(invoiceId, null, "INV-2026-001", null, "KES",
                new BigDecimal("45000.00"), KES_TO_USD, "USD", new BigDecimal("346.50"),
                LocalDateTime.now(), null, Instant.now(), Instant.now());
    }

    private Invoice invoiceWithBaseTotal(String baseTotal) {
        Invoice invoice = new Invoice();
        invoice.setBaseTotalAmount(new BigDecimal(baseTotal));
        return invoice;
    }

    private Icd11CodeResponse icd11Response(UUID id) {
        return new Icd11CodeResponse(id, "B54", "Malaria", Instant.now(), Instant.now());
    }

    private ProcedureResponse procedureResponse(UUID id) {
        return new ProcedureResponse(id, "P-001", "Blood smear",
                "Malaria microscopy", UUID.randomUUID(), true, null, Instant.now(), Instant.now());
    }

    @Test
    void createSavesClaimWithAllReferenceFields() {
        when(policyService.getEntityById(policyId)).thenReturn(policyCoveredBy(insurerId));
        when(benefitService.getEntityById(benefitId)).thenReturn(null);
        when(visitorService.getEntityById(visitorId)).thenReturn(visitorOnPolicy());
        when(insurerService.exists(insurerId)).thenReturn(true);
        when(invoiceService.getEntityById(invoiceId)).thenReturn(invoiceWithBaseTotal("45000.00"));
        when(currencyConversionService.getExchangeRate("KES", "USD")).thenReturn(KES_TO_USD);
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(visitorService.getById(visitorId)).thenReturn(visitorResponse());
        when(insurerService.getById(insurerId)).thenReturn(insurerResponse());
        when(invoiceService.getById(invoiceId)).thenReturn(invoiceResponse());
        when(icd11CodeService.getById(diagnosisId1)).thenReturn(icd11Response(diagnosisId1));
        when(icd11CodeService.getById(diagnosisId2)).thenReturn(icd11Response(diagnosisId2));
        when(procedureService.getById(procedureId1)).thenReturn(procedureResponse(procedureId1));

        ClaimResponse response = claimService.create(fullRequest());

        assertThat(response.visitorId()).isEqualTo(visitorId);
        assertThat(response.insurerId()).isEqualTo(insurerId);
        assertThat(response.prescription()).isEqualTo("Paracetamol 500mg twice daily");
        assertThat(response.diagnoses()).extracting(Icd11CodeResponse::code).containsExactly("B54", "B54");
        assertThat(response.procedures()).extracting(ProcedureResponse::procedureCode).containsExactly("P-001");
        assertThat(response.documentIds()).containsExactly(documentId);
        assertThat(response.status()).isEqualTo(ClaimStatus.OPEN);
        assertThat(response.visitor().fullName()).isEqualTo("Jane Traveler");
        assertThat(response.insurer().name()).isEqualTo("Jubilee Insurance");
        assertThat(response.invoices()).extracting(InvoiceResponse::invoiceNumber)
                .containsExactly("INV-2026-001");
        assertThat(response.claimedAmount()).isEqualByComparingTo("50000.00");
        assertThat(response.currency()).isEqualTo("KES");
        assertThat(response.baseCurrency()).isEqualTo("USD");
        assertThat(response.claimedAmountBase()).isEqualByComparingTo("45000.00");
        assertThat(response.exchangeRate()).isEqualByComparingTo("0.0077");
        assertThat(response.fxRateDate()).isNotNull();
        verify(claimRepository).save(any(Claim.class));
    }

    @Test
    void createConvertsRawClaimedAmountWhenNoInvoicesAttached() {
        when(policyService.getEntityById(policyId)).thenReturn(policyCoveredBy(insurerId));
        when(benefitService.getEntityById(benefitId)).thenReturn(null);
        when(insurerService.exists(insurerId)).thenReturn(true);
        when(currencyConversionService.getExchangeRate("KES", "USD")).thenReturn(KES_TO_USD);
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(insurerService.getById(insurerId)).thenReturn(insurerResponse());

        ClaimResponse response = claimService.create(baseRequest());

        assertThat(response.claimedAmount()).isEqualByComparingTo("50000.00");
        assertThat(response.currency()).isEqualTo("KES");
        assertThat(response.baseCurrency()).isEqualTo("USD");
        assertThat(response.claimedAmountBase()).isEqualByComparingTo("385.00");
        assertThat(response.exchangeRate()).isEqualByComparingTo("0.0077");
        assertThat(response.fxRateDate()).isNotNull();
    }

    @Test
    void createSavesClaimWithNewFieldsAbsent() {
        when(policyService.getEntityById(policyId)).thenReturn(policyCoveredBy(insurerId));
        when(benefitService.getEntityById(benefitId)).thenReturn(null);
        when(insurerService.exists(insurerId)).thenReturn(true);
        when(currencyConversionService.getExchangeRate("KES", "USD")).thenReturn(KES_TO_USD);
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(insurerService.getById(insurerId)).thenReturn(insurerResponse());

        ClaimResponse response = claimService.create(baseRequest());

        assertThat(response.visitorId()).isNull();
        assertThat(response.insurerId()).isEqualTo(insurerId);
        assertThat(response.prescription()).isNull();
        assertThat(response.diagnoses()).isEmpty();
        assertThat(response.procedures()).isEmpty();
        assertThat(response.invoices()).isEmpty();
        assertThat(response.documentIds()).isEmpty();
    }

    @Test
    void createRejectsVisitorNotOnClaimPolicy() {
        when(policyService.getEntityById(policyId)).thenReturn(policyCoveredBy(insurerId));
        when(benefitService.getEntityById(benefitId)).thenReturn(null);
        when(insurerService.exists(insurerId)).thenReturn(true);
        Visitor visitorOnOtherPolicy = new Visitor();
        visitorOnOtherPolicy.setId(visitorId);
        visitorOnOtherPolicy.setPolicyId(UUID.randomUUID());
        when(visitorService.getEntityById(visitorId)).thenReturn(visitorOnOtherPolicy);

        assertThatThrownBy(() -> claimService.create(fullRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Visitor does not belong to the claim's policy");
        verify(claimRepository, never()).save(any());
    }

    @Test
    void createRejectsDerivedInsurerThatDoesNotExist() {
        when(policyService.getEntityById(policyId)).thenReturn(policyCoveredBy(insurerId));
        when(benefitService.getEntityById(benefitId)).thenReturn(null);
        when(insurerService.exists(insurerId)).thenReturn(false);

        assertThatThrownBy(() -> claimService.create(baseRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(claimRepository, never()).save(any());
    }

    @Test
    void createRejectsUnknownInvoice() {
        when(policyService.getEntityById(policyId)).thenReturn(policyCoveredBy(insurerId));
        when(benefitService.getEntityById(benefitId)).thenReturn(null);
        when(visitorService.getEntityById(visitorId)).thenReturn(visitorOnPolicy());
        when(insurerService.exists(insurerId)).thenReturn(true);
        when(invoiceService.getEntityById(invoiceId))
                .thenThrow(new ResourceNotFoundException("Invoice", invoiceId));

        assertThatThrownBy(() -> claimService.create(fullRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(claimRepository, never()).save(any());
    }

    @Test
    void getByIdReturnsClaim() {
        UUID id = UUID.randomUUID();
        Claim claim = claimMapper.toEntity(baseRequest());
        claim.setId(id);
        when(claimRepository.findById(id)).thenReturn(Optional.of(claim));

        ClaimResponse response = claimService.getById(id);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.status()).isEqualTo(ClaimStatus.SUBMITTED);
    }

    @Test
    void listByVisitorReturnsClaimsForThatVisitor() {
        Claim claim = claimMapper.toEntity(baseRequest());
        claim.setId(UUID.randomUUID());
        claim.setVisitorId(visitorId);
        when(claimRepository.findAllByVisitorId(visitorId)).thenReturn(List.of(claim));
        when(visitorService.getById(visitorId)).thenReturn(visitorResponse());

        List<ClaimResponse> responses = claimService.listByVisitor(visitorId);

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().visitorId()).isEqualTo(visitorId);
    }

    @Test
    void getByIdThrowsWhenUnknown() {
        UUID id = UUID.randomUUID();
        when(claimRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimService.getById(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void decideApprovesWithinClaimedAmount() {
        UUID id = UUID.randomUUID();
        Claim claim = claimMapper.toEntity(baseRequest());
        claim.setId(id);
        claim.setStatus(ClaimStatus.UNDER_REVIEW);
        when(claimRepository.findById(id)).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(policyService.getEntityById(policyId)).thenReturn(policyCoveredBy(insurerId));

        ClaimResponse response = claimService.decide(id,
                new ClaimDecisionRequest(ClaimStatus.APPROVED, new BigDecimal("40000.00"), "Approved"));

        assertThat(response.status()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(response.approvedAmount()).isEqualByComparingTo("40000.00");
        verify(eventPublisher).publish(any(), any());
    }

    @Test
    void decideRejectsAmountAboveClaimed() {
        UUID id = UUID.randomUUID();
        Claim claim = claimMapper.toEntity(baseRequest());
        claim.setId(id);
        claim.setStatus(ClaimStatus.UNDER_REVIEW);
        when(claimRepository.findById(id)).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> claimService.decide(id,
                new ClaimDecisionRequest(ClaimStatus.APPROVED, new BigDecimal("60000.00"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Approved amount cannot exceed claimed amount");
        verify(eventPublisher, never()).publish(any(), any());
    }

    @Test
    void deleteRemovesClaim() {
        UUID id = UUID.randomUUID();
        Claim claim = claimMapper.toEntity(baseRequest());
        claim.setId(id);
        when(claimRepository.findById(id)).thenReturn(Optional.of(claim));

        claimService.delete(id);

        verify(claimRepository).delete(claim);
    }

    @Test
    void attachInvoiceAttachesToOpenClaimAndReturnsPopulatedInvoices() {
        UUID claimId = UUID.randomUUID();
        Claim claim = claimMapper.toEntity(baseRequest());
        claim.setId(claimId);
        claim.setStatus(ClaimStatus.OPEN);

        when(claimRepository.findById(claimId)).thenReturn(Optional.of(claim));
        when(invoiceService.getEntityById(invoiceId)).thenReturn(invoiceWithBaseTotal("45000.00"));
        when(currencyConversionService.getExchangeRate("KES", "USD")).thenReturn(KES_TO_USD);
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(invoiceService.getById(invoiceId)).thenReturn(invoiceResponse());

        ClaimResponse response = claimService.attachInvoice(claimId, new AttachInvoiceRequest(invoiceId));

        assertThat(response.id()).isEqualTo(claimId);
        assertThat(response.status()).isEqualTo(ClaimStatus.SUBMITTED);
        assertThat(response.invoices()).extracting(InvoiceResponse::invoiceNumber)
                .containsExactly("INV-2026-001");
        assertThat(response.claimedAmountBase()).isEqualByComparingTo("45000.00");
        verify(claimRepository).save(claim);
    }

    @Test
    void attachInvoiceAggregatesBaseAmountsAcrossAllInvoices() {
        UUID claimId = UUID.randomUUID();
        UUID secondInvoiceId = UUID.randomUUID();
        Claim claim = claimMapper.toEntity(baseRequest());
        claim.setId(claimId);
        claim.setStatus(ClaimStatus.OPEN);
        claim.getInvoiceIds().add(secondInvoiceId);

        when(claimRepository.findById(claimId)).thenReturn(Optional.of(claim));
        when(invoiceService.getEntityById(invoiceId)).thenReturn(invoiceWithBaseTotal("100.00"));
        when(invoiceService.getEntityById(secondInvoiceId)).thenReturn(invoiceWithBaseTotal("250.00"));
        when(currencyConversionService.getExchangeRate("KES", "USD")).thenReturn(KES_TO_USD);
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(invoiceService.getById(invoiceId)).thenReturn(invoiceResponse());
        when(invoiceService.getById(secondInvoiceId)).thenReturn(invoiceResponse());

        ClaimResponse response = claimService.attachInvoice(claimId, new AttachInvoiceRequest(invoiceId));

        assertThat(response.claimedAmountBase()).isEqualByComparingTo("350.00");
    }

    @Test
    void updateRecomputesBaseAmounts() {
        UUID id = UUID.randomUUID();
        Claim claim = claimMapper.toEntity(baseRequest());
        claim.setId(id);
        claim.setStatus(ClaimStatus.OPEN);
        when(claimRepository.findById(id)).thenReturn(Optional.of(claim));
        when(policyService.getEntityById(policyId)).thenReturn(policyCoveredBy(insurerId));
        when(benefitService.getEntityById(benefitId)).thenReturn(null);
        when(visitorService.getEntityById(visitorId)).thenReturn(visitorOnPolicy());
        when(insurerService.exists(insurerId)).thenReturn(true);
        when(invoiceService.getEntityById(invoiceId)).thenReturn(invoiceWithBaseTotal("45000.00"));
        when(currencyConversionService.getExchangeRate("KES", "USD")).thenReturn(KES_TO_USD);
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(insurerService.getById(insurerId)).thenReturn(insurerResponse());
        when(invoiceService.getById(invoiceId)).thenReturn(invoiceResponse());

        ClaimResponse response = claimService.update(id, fullRequest());

        assertThat(response.claimedAmountBase()).isEqualByComparingTo("45000.00");
        assertThat(response.exchangeRate()).isEqualByComparingTo("0.0077");
        assertThat(response.invoices()).extracting(InvoiceResponse::invoiceNumber)
                .containsExactly("INV-2026-001");
        verify(claimRepository).save(claim);
    }

    @Test
    void attachInvoiceRejectsUnderReviewClaim() {
        UUID claimId = UUID.randomUUID();
        Claim claim = claimMapper.toEntity(baseRequest());
        claim.setId(claimId);
        claim.setStatus(ClaimStatus.UNDER_REVIEW);

        when(claimRepository.findById(claimId)).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> claimService.attachInvoice(claimId, new AttachInvoiceRequest(invoiceId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Claim is not open for attaching invoices: UNDER_REVIEW");
        verify(claimRepository, never()).save(any());
    }

    @Test
    void attachInvoiceThrowsConflictWhenClaimIsNotOpen() {
        UUID claimId = UUID.randomUUID();
        Claim claim = claimMapper.toEntity(baseRequest());
        claim.setId(claimId);
        claim.setStatus(ClaimStatus.APPROVED);

        when(claimRepository.findById(claimId)).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> claimService.attachInvoice(claimId, new AttachInvoiceRequest(invoiceId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Claim is not open for attaching invoices: APPROVED");
        verify(claimRepository, never()).save(any());
    }

    @Test
    void attachInvoiceThrowsNotFoundWhenClaimDoesNotExist() {
        UUID claimId = UUID.randomUUID();
        when(claimRepository.findById(claimId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimService.attachInvoice(claimId, new AttachInvoiceRequest(invoiceId)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(claimRepository, never()).save(any());
    }

    @Test
    void attachInvoiceThrowsNotFoundWhenInvoiceDoesNotExist() {
        UUID claimId = UUID.randomUUID();
        Claim claim = claimMapper.toEntity(baseRequest());
        claim.setId(claimId);
        claim.setStatus(ClaimStatus.OPEN);

        when(claimRepository.findById(claimId)).thenReturn(Optional.of(claim));
        when(invoiceService.getEntityById(invoiceId))
                .thenThrow(new ResourceNotFoundException("Invoice", invoiceId));

        assertThatThrownBy(() -> claimService.attachInvoice(claimId, new AttachInvoiceRequest(invoiceId)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(claimRepository, never()).save(any());
    }
}
