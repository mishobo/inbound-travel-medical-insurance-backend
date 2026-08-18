package com.travel.insurance.notification;

import com.travel.insurance.common.email.EmailAttachment;
import com.travel.insurance.common.email.EmailService;
import com.travel.insurance.common.util.LogoUrlNormalizer;
import com.travel.insurance.config.MailProperties;
import com.travel.insurance.insurer.InsurerService;
import com.travel.insurance.insurer.dto.InsurerResponse;
import com.travel.insurance.notification.PolicyDocumentData.BenefitLine;
import com.travel.insurance.policy.Policy;
import com.travel.insurance.policy.PolicyService;
import com.travel.insurance.visitor.Visitor;
import com.travel.insurance.visitor.VisitorCreatedEvent;
import com.travel.insurance.visitor.VisitorService;
import com.travel.insurance.visitor.VisitorStatus;
import com.travel.insurance.visitor.VisitorStatusChangedEvent;
import com.travel.insurance.visitorbenefit.VisitorBenefitService;
import com.travel.insurance.visitorbenefit.dto.VisitorBenefitResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Emails the visitor their personalized policy certificate once their cover is
 * active — either on a status transition to ACTIVE, or on creation when the
 * visitor is created already ACTIVE (the default). Both paths are gated on the
 * ACTIVE status so exactly one certificate goes out per activation.
 * Unlike {@code visitorbenefit.VisitorStatusChangedListener}
 * (which stays synchronous and in-transaction because it must mirror the
 * status onto VisitorBenefit rows consistently), this listener uses
 * {@code AFTER_COMMIT}: sending mail over SMTP inside the same transaction
 * that changed the visitor's status would risk rolling back a legitimate
 * status change if the mail server is slow or unreachable. Any failure here
 * is caught and logged, never propagated — a broken mail server must never
 * be able to affect the visitor status API's correctness. Re-activation
 * (e.g. ACTIVE → SUSPENDED → ACTIVE) intentionally re-sends the certificate;
 * that's treated as a new, valid activation rather than a duplicate to guard
 * against.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VisitorActivatedNotificationListener {

    private static final String POLICY_DOCUMENT_RESOURCE = "templates/Policy_Document_July_2026.pdf";
    private static final String POLICY_DOCUMENT_ATTACHMENT_NAME = "Policy_Document_July_2026.pdf";

    private byte[] policyDocumentCache;

    private final VisitorService visitorService;
    private final PolicyService policyService;
    private final VisitorBenefitService visitorBenefitService;
    private final InsurerService insurerService;
    private final PolicyDocumentRenderer renderer;
    private final EmailService emailService;
    private final MailProperties mailProperties;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVisitorStatusChanged(VisitorStatusChangedEvent event) {
        if (event.newStatus() != VisitorStatus.ACTIVE) {
            return;
        }
        sendActivationDocumentQuietly(event.visitorId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVisitorCreated(VisitorCreatedEvent event) {
        if (visitorService.getEntityById(event.visitorId()).getVisitorStatus() != VisitorStatus.ACTIVE) {
            return;
        }
        sendActivationDocumentQuietly(event.visitorId());
    }

    private void sendActivationDocumentQuietly(UUID visitorId) {
        try {
            sendActivationDocument(visitorId);
        } catch (Exception ex) {
            log.error("Failed to generate/send policy document for visitor {}: {}",
                    visitorId, ex.getMessage(), ex);
        }
    }

    private void sendActivationDocument(UUID visitorId) {
        Visitor visitor = visitorService.getEntityById(visitorId);
        Policy policy = policyService.getEntityById(visitor.getPolicyId());
        List<VisitorBenefitResponse> visitorBenefits = visitorBenefitService.listAllByVisitor(visitorId);
        if (visitorBenefits.isEmpty()) {
            log.warn("Visitor {} activated with no assigned benefits yet; sending certificate without a schedule",
                    visitorId);
        }

        InsurerResponse insurer = insurerService.getById(policy.getInsurerId());
        List<String> insurerNames = List.of(insurer.name());
        String underwriterLogoUrl = insurer.logoUrl() != null && !insurer.logoUrl().isBlank()
                ? LogoUrlNormalizer.normalize(insurer.logoUrl())
                : null;
        List<BenefitLine> benefitLines = visitorBenefits.stream()
                .map(vb -> new BenefitLine(vb.benefitName(), vb.limitAmount()))
                .toList();

        PolicyDocumentData data = new PolicyDocumentData(
                visitor.getFullName(),
                visitor.getPassportNumber(),
                visitor.getDateOfBirth(),
                visitor.getGender(),
                visitor.getNationality(),
                visitor.getAddress(),
                visitor.getEmail(),
                visitor.getPhoneNumber(),
                visitor.getDateIn(),
                visitor.getDateOut(),
                visitor.getReasonForTravel(),
                policy.getPolicyNumber(),
                policy.getPolicyType(),
                insurerNames,
                underwriterLogoUrl,
                benefitLines,
                mailProperties.getEmergencyAssistance().getPhone(),
                mailProperties.getEmergencyAssistance().getEmail());

        byte[] pdf = renderer.renderPdf(data);
        List<EmailAttachment> attachments = new ArrayList<>();
        attachments.add(new EmailAttachment(
                "policy-certificate-" + policy.getPolicyNumber() + ".pdf", pdf));
        byte[] policyDocument = loadPolicyDocument();
        if (policyDocument != null) {
            attachments.add(new EmailAttachment(POLICY_DOCUMENT_ATTACHMENT_NAME, policyDocument));
        }
        emailService.send(
                mailProperties.getFrom(),
                visitor.getEmail(),
                "Your Travel Insurance Policy Document",
                "<p>Dear " + visitor.getFullName() + ",</p>"
                        + "<p>Your travel insurance cover is now active. Your policy certificate is attached.</p>",
                attachments);
    }

    /**
     * Loads the bundled policy wording PDF from the classpath, cached after the
     * first read. A load failure is logged and returns {@code null} so the
     * certificate still goes out without the supplementary document.
     */
    private synchronized byte[] loadPolicyDocument() {
        if (policyDocumentCache == null) {
            try {
                policyDocumentCache = new ClassPathResource(POLICY_DOCUMENT_RESOURCE)
                        .getInputStream().readAllBytes();
            } catch (IOException ex) {
                log.error("Could not load bundled policy document {}: {}",
                        POLICY_DOCUMENT_RESOURCE, ex.getMessage(), ex);
                return null;
            }
        }
        return policyDocumentCache;
    }
}
