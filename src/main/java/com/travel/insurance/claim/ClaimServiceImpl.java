package com.travel.insurance.claim;

import com.travel.insurance.benefit.BenefitService;
import com.travel.insurance.claim.dto.AttachInvoiceRequest;
import com.travel.insurance.claim.dto.ClaimDecisionRequest;
import com.travel.insurance.claim.dto.ClaimRequest;
import com.travel.insurance.claim.dto.ClaimResponse;
import com.travel.insurance.common.exception.ResourceNotFoundException;
import com.travel.insurance.common.messaging.EventPublisher;
import com.travel.insurance.common.service.CurrencyConversionService;
import com.travel.insurance.common.util.AuthenticatedUser;
import com.travel.insurance.common.util.SecurityUtils;
import com.travel.insurance.config.RabbitConfig;
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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ClaimServiceImpl implements ClaimService {

    private static final Set<ClaimStatus> DECISION_STATUSES = EnumSet.of(
            ClaimStatus.UNDER_REVIEW,
            ClaimStatus.APPROVED,
            ClaimStatus.PARTIALLY_APPROVED,
            ClaimStatus.REJECTED);

    private static final Set<ClaimStatus> OPEN_STATUSES = EnumSet.of(
            ClaimStatus.OPEN,
            ClaimStatus.SUBMITTED,
            ClaimStatus.UNDER_REVIEW);

    private final ClaimRepository claimRepository;
    private final ClaimMapper claimMapper;
    private final PolicyService policyService;
    private final BenefitService benefitService;
    private final VisitorService visitorService;
    private final InsurerService insurerService;
    private final InvoiceService invoiceService;
    private final Icd11CodeService icd11CodeService;
    private final ProcedureService procedureService;
    private final CurrencyConversionService currencyConversionService;
    private final EventPublisher eventPublisher;

    @Override
    public ClaimResponse create(ClaimRequest request) {
        UUID insurerId = validateReferences(request);
        Claim claim = claimMapper.toEntity(request);
        claim.setStatus(ClaimStatus.OPEN);
        claim.setInsurerId(insurerId);
        applyBaseConversion(claim);
        claim = claimRepository.save(claim);
        return toResponse(claim);
    }

    @Override
    public ClaimResponse update(UUID id, ClaimRequest request) {
        Claim claim = getEntity(id);
        if (!OPEN_STATUSES.contains(claim.getStatus())) {
            throw new IllegalStateException("Claim is not open for updates: " + claim.getStatus());
        }
        UUID insurerId = validateReferences(request);
        claimMapper.updateEntity(claim, request);
        claim.setInsurerId(insurerId);
        applyBaseConversion(claim);
        return toResponse(claimRepository.save(claim));
    }

    @Override
    public ClaimResponse attachInvoice(UUID id, AttachInvoiceRequest request) {
        Claim claim = getEntity(id);
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new IllegalStateException("Claim is not open for attaching invoices: " + claim.getStatus());
        }
        invoiceService.getEntityById(request.invoiceId());
        claim.getInvoiceIds().add(request.invoiceId());
        applyBaseConversion(claim);
        claim.setStatus(ClaimStatus.SUBMITTED);
        Claim saved = claimRepository.save(claim);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ClaimResponse getById(UUID id) {
        return toResponse(getEntity(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ClaimResponse> list(Pageable pageable) {
        return findScoped(pageable).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClaimResponse> listByVisitor(UUID visitorId) {
        return claimRepository.findAllByVisitorId(visitorId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public ClaimResponse decide(UUID id, ClaimDecisionRequest request) {
        Claim claim = getEntity(id);
        if (!OPEN_STATUSES.contains(claim.getStatus())) {
            throw new IllegalStateException("Claim is not open for decisions: " + claim.getStatus());
        }
        if (!DECISION_STATUSES.contains(request.status())) {
            throw new IllegalArgumentException("Decision status must be one of " + DECISION_STATUSES);
        }
        applyDecision(claim, request);
        Claim saved = claimRepository.save(claim);
        publishIfApproved(saved);
        return toResponse(saved);
    }

    @Override
    public ClaimResponse markPaid(UUID id) {
        Claim claim = getEntity(id);
        if (claim.getStatus() != ClaimStatus.APPROVED && claim.getStatus() != ClaimStatus.PARTIALLY_APPROVED) {
            throw new IllegalStateException("Only approved claims can be marked as paid");
        }
        claim.setStatus(ClaimStatus.PAID);
        return toResponse(claimRepository.save(claim));
    }

    @Override
    public void delete(UUID id) {
        claimRepository.delete(getEntity(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClaimUtilizationTotal> sumClaimedAmountsByVisitorAndBenefit(
            Collection<UUID> visitorIds, Collection<UUID> benefitIds) {
        if (visitorIds.isEmpty() || benefitIds.isEmpty()) {
            return List.of();
        }
        return claimRepository.sumClaimedAmountsByVisitorAndBenefit(visitorIds, benefitIds);
    }

    private void applyDecision(Claim claim, ClaimDecisionRequest request) {
        claim.setStatus(request.status());
        claim.setDecisionReason(request.reason());
        if (request.status() == ClaimStatus.REJECTED) {
            claim.setApprovedAmount(BigDecimal.ZERO);
            return;
        }
        if (request.status() == ClaimStatus.UNDER_REVIEW) {
            return;
        }
        BigDecimal approved = request.approvedAmount() != null
                ? request.approvedAmount()
                : claim.getClaimedAmount();
        if (approved.compareTo(claim.getClaimedAmount()) > 0) {
            throw new IllegalArgumentException("Approved amount cannot exceed claimed amount");
        }
        claim.setApprovedAmount(approved);
    }

    private UUID validateReferences(ClaimRequest request) {
        Policy policy = policyService.getEntityById(request.policyId());
        benefitService.getEntityById(request.benefitId());
        
        UUID insurerId = policy.getInsurerId();
        if (!insurerService.exists(insurerId)) {
            throw new ResourceNotFoundException("Insurer", insurerId);
        }

        if (request.visitorId() != null) {
            Visitor visitor = visitorService.getEntityById(request.visitorId());
            if (!policy.getId().equals(visitor.getPolicyId())) {
                throw new IllegalStateException("Visitor does not belong to the claim's policy");
            }
        }

        if (request.invoiceIds() != null) {
            request.invoiceIds().forEach(invoiceService::getEntityById);
        }
        return insurerId;
    }

    private void applyBaseConversion(Claim claim) {
        BigDecimal rate = currencyConversionService.getExchangeRate(claim.getCurrency(), "USD");
        claim.setBaseCurrency("USD");
        claim.setExchangeRate(rate);
        claim.setFxRateDate(LocalDateTime.now());
        if (claim.getInvoiceIds().isEmpty()) {
            claim.setClaimedAmountBase(toBase(claim.getClaimedAmount(), rate));
        } else {
            claim.setClaimedAmountBase(aggregateInvoiceBaseAmounts(claim));
        }
    }

    private BigDecimal aggregateInvoiceBaseAmounts(Claim claim) {
        BigDecimal total = BigDecimal.ZERO;
        for (UUID invoiceId : claim.getInvoiceIds()) {
            Invoice invoice = invoiceService.getEntityById(invoiceId);
            if (invoice.getBaseTotalAmount() != null) {
                total = total.add(invoice.getBaseTotalAmount());
            }
        }
        return total;
    }

    private static BigDecimal toBase(BigDecimal amount, BigDecimal rate) {
        if (amount == null) {
            return null;
        }
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private Page<Claim> findScoped(Pageable pageable) {
        AuthenticatedUser user = SecurityUtils.currentUser().orElse(null);
        if (user != null && user.roles().contains("PROVIDER_USER")) {
            return claimRepository.findAllByServiceProviderId(user.organizationId(), pageable);
        }
        return claimRepository.findAll(pageable);
    }

    private void publishIfApproved(Claim claim) {
        if (claim.getStatus() != ClaimStatus.APPROVED && claim.getStatus() != ClaimStatus.PARTIALLY_APPROVED) {
            return;
        }
        Policy policy = policyService.getEntityById(claim.getPolicyId());
        eventPublisher.publish(RabbitConfig.CLAIM_APPROVED_KEY, Map.of(
                "claimId", claim.getId().toString(),
                "policyId", claim.getPolicyId().toString(),
                "insurerId", policy.getInsurerId().toString(),
                "approvedAmount", claim.getApprovedAmount().toPlainString(),
                "status", claim.getStatus().name()));
    }

    private Claim getEntity(UUID id) {
        return claimRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Claim", id));
    }

    private ClaimResponse toResponse(Claim claim) {
        VisitorResponse visitor = claim.getVisitorId() != null
                ? visitorService.getById(claim.getVisitorId())
                : null;
        InsurerResponse insurer = claim.getInsurerId() != null
                ? insurerService.getById(claim.getInsurerId())
                : null;
        List<InvoiceResponse> invoices = claim.getInvoiceIds().stream()
                .map(invoiceService::getById)
                .toList();
        List<Icd11CodeResponse> diagnoses = claim.getDiagnosisIds().stream()
                .map(this::resolveDiagnosis)
                .filter(Objects::nonNull)
                .toList();
        List<ProcedureResponse> procedures = claim.getProcedureIds().stream()
                .map(this::resolveProcedure)
                .filter(Objects::nonNull)
                .toList();
        return new ClaimResponse(
                claim.getId(),
                claim.getPolicyId(),
                claim.getBenefitId(),
                claim.getServiceProviderId(),
                claim.getPreauthorizationId(),
                claim.getVisitorId(),
                visitor,
                claim.getInsurerId(),
                insurer,
                claim.getClaimedAmount(),
                claim.getCurrency(),
                claim.getClaimedAmountBase(),
                claim.getExchangeRate(),
                claim.getBaseCurrency(),
                claim.getFxRateDate(),
                claim.getApprovedAmount(),
                claim.getDescription(),
                claim.getPrescription(),
                diagnoses,
                procedures,
                invoices,
                Set.copyOf(claim.getDocumentIds()),
                claim.getDecisionReason(),
                claim.getStatus(),
                claim.getCreatedDate(),
                claim.getUpdatedDate()
        );
    }

    private Icd11CodeResponse resolveDiagnosis(UUID id) {
        try {
            return icd11CodeService.getById(id);
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }

    private ProcedureResponse resolveProcedure(UUID id) {
        try {
            return procedureService.getById(id);
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }
}
