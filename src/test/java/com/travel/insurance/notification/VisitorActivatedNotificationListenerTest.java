package com.travel.insurance.notification;

import com.travel.insurance.common.email.EmailAttachment;
import com.travel.insurance.common.email.EmailService;
import com.travel.insurance.config.MailProperties;
import com.travel.insurance.insurer.InsurerService;
import com.travel.insurance.insurer.dto.InsurerResponse;
import com.travel.insurance.policy.Policy;
import com.travel.insurance.policy.PolicyService;
import com.travel.insurance.policy.PolicyType;
import com.travel.insurance.visitor.Gender;
import com.travel.insurance.visitor.MaritalStatus;
import com.travel.insurance.visitor.Visitor;
import com.travel.insurance.visitor.VisitorCreatedEvent;
import com.travel.insurance.visitor.VisitorService;
import com.travel.insurance.visitor.VisitorStatus;
import com.travel.insurance.visitor.VisitorStatusChangedEvent;
import com.travel.insurance.visitorbenefit.VisitorBenefitService;
import com.travel.insurance.visitorbenefit.dto.VisitorBenefitResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisitorActivatedNotificationListenerTest {

    @Mock
    private VisitorService visitorService;

    @Mock
    private PolicyService policyService;

    @Mock
    private VisitorBenefitService visitorBenefitService;

    @Mock
    private InsurerService insurerService;

    @Mock
    private PolicyDocumentRenderer renderer;

    @Mock
    private EmailService emailService;

    private VisitorActivatedNotificationListener listener;

    private final UUID visitorId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private final UUID insurerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MailProperties mailProperties = new MailProperties();
        mailProperties.setFrom("no-reply@travelinsurance.example");
        mailProperties.getEmergencyAssistance().setPhone("+254 700 000000");
        mailProperties.getEmergencyAssistance().setEmail("assistance@example.com");

        listener = new VisitorActivatedNotificationListener(
                visitorService, policyService, visitorBenefitService, insurerService,
                renderer, emailService, mailProperties);
    }

    private Visitor sampleVisitor() {
        Visitor visitor = new Visitor();
        visitor.setId(visitorId);
        visitor.setPolicyId(policyId);
        visitor.setFullName("Jane Traveler");
        visitor.setPassportNumber("P1234567");
        visitor.setDateOfBirth(LocalDate.of(1990, 5, 12));
        visitor.setGender(Gender.FEMALE);
        visitor.setNationality("Germany");
        visitor.setAddress("12 Example Street, Berlin");
        visitor.setEmail("jane.traveler@example.com");
        visitor.setPhoneNumber("+254700000000");
        visitor.setDateIn(LocalDate.of(2026, 8, 1));
        visitor.setDateOut(LocalDate.of(2026, 11, 1));
        visitor.setMaritalStatus(MaritalStatus.SINGLE);
        visitor.setReasonForTravel("Tourism");
        visitor.setFacePhotoUrl("https://storage.example.com/photos/jane.jpg");
        return visitor;
    }

    private Policy samplePolicy() {
        Policy policy = new Policy();
        policy.setPolicyNumber("POL-0001");
        policy.setPolicyType(PolicyType.IPMI_61_DAYS_TO_12_MONTHS);
        policy.setInsurerId(insurerId);
        return policy;
    }

    @Test
    void ignoresTransitionsToNonActiveStatus() {
        listener.onVisitorStatusChanged(new VisitorStatusChangedEvent(visitorId, VisitorStatus.SUSPENDED));

        verifyNoInteractions(visitorService, policyService, visitorBenefitService, insurerService,
                renderer, emailService);
    }

    @Test
    void sendsCertificateEmailWhenVisitorBecomesActive() {
        when(visitorService.getEntityById(visitorId)).thenReturn(sampleVisitor());
        when(policyService.getEntityById(policyId)).thenReturn(samplePolicy());
        when(visitorBenefitService.listAllByVisitor(visitorId)).thenReturn(List.of(
                new VisitorBenefitResponse(UUID.randomUUID(), visitorId, UUID.randomUUID(),
                        "Medical Expenses", new BigDecimal("20000.00"), BigDecimal.ZERO,
                        new BigDecimal("20000.00"),
                        VisitorStatus.ACTIVE, Instant.now(), Instant.now())));
        when(insurerService.getById(insurerId)).thenReturn(new InsurerResponse(
                insurerId, "Acme Insurance", "contact@acme.example", null, null,
                "https://cdn.example/acme.png", null, null, Instant.now(), Instant.now()));
        when(renderer.renderPdf(any(PolicyDocumentData.class))).thenReturn("%PDF-1.4".getBytes());

        listener.onVisitorStatusChanged(new VisitorStatusChangedEvent(visitorId, VisitorStatus.ACTIVE));

        ArgumentCaptor<PolicyDocumentData> dataCaptor = ArgumentCaptor.forClass(PolicyDocumentData.class);
        verify(renderer).renderPdf(dataCaptor.capture());
        assertThat(dataCaptor.getValue().visitorFullName()).isEqualTo("Jane Traveler");
        assertThat(dataCaptor.getValue().insurerNames()).containsExactly("Acme Insurance");
        assertThat(dataCaptor.getValue().underwriterLogoUrl()).isEqualTo("https://cdn.example/acme.png");
        assertThat(dataCaptor.getValue().benefits()).hasSize(1);

        ArgumentCaptor<List<EmailAttachment>> attachmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(emailService).send(
                eq("no-reply@travelinsurance.example"),
                eq("jane.traveler@example.com"),
                anyString(),
                anyString(),
                attachmentsCaptor.capture());
        assertThat(attachmentsCaptor.getValue())
                .extracting(EmailAttachment::filename)
                .containsExactly("policy-certificate-POL-0001.pdf", "Policy_Document_July_2026.pdf");
        assertThat(attachmentsCaptor.getValue().get(0).content()).isEqualTo("%PDF-1.4".getBytes());
        assertThat(attachmentsCaptor.getValue().get(1).content()).isNotEmpty();
    }

    @Test
    void normalizesLegacyDropboxLogoUrlBeforeRendering() {
        when(visitorService.getEntityById(visitorId)).thenReturn(sampleVisitor());
        when(policyService.getEntityById(policyId)).thenReturn(samplePolicy());
        when(visitorBenefitService.listAllByVisitor(visitorId)).thenReturn(List.of());
        when(insurerService.getById(insurerId)).thenReturn(new InsurerResponse(
                insurerId, "Acme Insurance", "contact@acme.example", null, null,
                "https://www.dropbox.com/scl/fi/abc/ga-logo.png?rlkey=key&dl=0", null,
                null, Instant.now(), Instant.now()));
        when(renderer.renderPdf(any(PolicyDocumentData.class))).thenReturn("%PDF-1.4".getBytes());

        listener.onVisitorStatusChanged(new VisitorStatusChangedEvent(visitorId, VisitorStatus.ACTIVE));

        ArgumentCaptor<PolicyDocumentData> dataCaptor = ArgumentCaptor.forClass(PolicyDocumentData.class);
        verify(renderer).renderPdf(dataCaptor.capture());
        assertThat(dataCaptor.getValue().underwriterLogoUrl())
                .isEqualTo("https://dl.dropboxusercontent.com/scl/fi/abc/ga-logo.png?rlkey=key&dl=1");
    }

    @Test
    void doesNotPropagateWhenRendererThrows() {
        when(visitorService.getEntityById(visitorId)).thenReturn(sampleVisitor());
        when(policyService.getEntityById(policyId)).thenReturn(samplePolicy());
        when(visitorBenefitService.listAllByVisitor(visitorId)).thenReturn(List.of());
        when(insurerService.getById(insurerId)).thenReturn(new InsurerResponse(
                insurerId, "Acme Insurance", "contact@acme.example", null, null, null, null, null, Instant.now(), Instant.now()));
        when(renderer.renderPdf(any(PolicyDocumentData.class)))
                .thenThrow(new IllegalStateException("PDF rendering failed"));

        assertThatCode(() -> listener.onVisitorStatusChanged(
                new VisitorStatusChangedEvent(visitorId, VisitorStatus.ACTIVE)))
                .doesNotThrowAnyException();

        verify(emailService, never()).send(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void sendsCertificateEvenWhenNoBenefitsAssignedYet() {
        when(visitorService.getEntityById(visitorId)).thenReturn(sampleVisitor());
        when(policyService.getEntityById(policyId)).thenReturn(samplePolicy());
        when(visitorBenefitService.listAllByVisitor(visitorId)).thenReturn(List.of());
        when(insurerService.getById(insurerId)).thenReturn(new InsurerResponse(
                insurerId, "Acme Insurance", "contact@acme.example", null, null, null, null, null, Instant.now(), Instant.now()));
        when(renderer.renderPdf(any(PolicyDocumentData.class))).thenReturn("%PDF-1.4".getBytes());

        listener.onVisitorStatusChanged(new VisitorStatusChangedEvent(visitorId, VisitorStatus.ACTIVE));

        verify(emailService).send(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void sendsCertificateWhenVisitorCreatedAlreadyActive() {
        when(visitorService.getEntityById(visitorId)).thenReturn(sampleVisitor());
        when(policyService.getEntityById(policyId)).thenReturn(samplePolicy());
        when(visitorBenefitService.listAllByVisitor(visitorId)).thenReturn(List.of());
        when(insurerService.getById(insurerId)).thenReturn(new InsurerResponse(
                insurerId, "Acme Insurance", "contact@acme.example", null, null, null, null, null, Instant.now(), Instant.now()));
        when(renderer.renderPdf(any(PolicyDocumentData.class))).thenReturn("%PDF-1.4".getBytes());

        listener.onVisitorCreated(new VisitorCreatedEvent(visitorId, policyId));

        verify(emailService).send(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void doesNotSendCertificateWhenCreatedVisitorNotActive() {
        Visitor pending = sampleVisitor();
        pending.setVisitorStatus(VisitorStatus.PENDING);
        when(visitorService.getEntityById(visitorId)).thenReturn(pending);

        listener.onVisitorCreated(new VisitorCreatedEvent(visitorId, policyId));

        verifyNoInteractions(policyService, visitorBenefitService, insurerService, renderer, emailService);
    }
}
