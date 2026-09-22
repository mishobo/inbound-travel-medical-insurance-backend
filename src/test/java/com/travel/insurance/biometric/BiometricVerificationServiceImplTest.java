package com.travel.insurance.biometric;

import com.travel.insurance.biometric.client.BiometricMicroserviceClient;
import com.travel.insurance.biometric.client.BiometricMicroserviceRequest;
import com.travel.insurance.biometric.client.BiometricMicroserviceResponse;
import com.travel.insurance.biometric.dto.BiometricCallbackPayload;
import com.travel.insurance.biometric.dto.BiometricVerificationRequest;
import com.travel.insurance.biometric.dto.BiometricVerificationResponse;
import com.travel.insurance.biometric.dto.BiometricVerificationResponse;
import com.travel.insurance.common.exception.ResourceNotFoundException;
import com.travel.insurance.common.messaging.EventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatusCode;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BiometricVerificationServiceImplTest {

    @Mock
    private BiometricVerificationRepository repository;

    @Mock
    private BiometricMicroserviceClient microserviceClient;

    @Mock
    private EventPublisher eventPublisher;

    private final BiometricVerificationMapper mapper = new BiometricVerificationMapper();

    private BiometricVerificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BiometricVerificationServiceImpl(repository, mapper, microserviceClient, eventPublisher);
    }

    @Test
    void createCallsMicroserviceAndStoresEmbededDetails() {
        when(repository.save(any(BiometricVerification.class))).thenAnswer(invocation -> {
            BiometricVerification verification = invocation.getArgument(0);
            if (verification.getId() == null) {
                verification.setId(UUID.randomUUID());
            }
            return verification;
        });
        when(microserviceClient.createVerification(any(BiometricMicroserviceRequest.class)))
                .thenReturn(new BiometricMicroserviceResponse("ekyc-req-1", "rp-1", "token-1",
                        "2026-08-04T12:00:00Z", "https://micro.example/embeded?request_id=ekyc-req-1"));

        BiometricVerificationResponse response = service.create(
                new BiometricVerificationRequest("39289507", "citizen", "VMI-POL-001", "WS-NRB-014"));

        assertThat(response.status()).isEqualTo(BiometricVerificationStatus.PENDING);
        assertThat(response.ekycRequestId()).isEqualTo("ekyc-req-1");
        assertThat(response.embededToken()).isEqualTo("token-1");
        assertThat(response.requestUrl()).contains("ekyc-req-1");

        ArgumentCaptor<BiometricMicroserviceRequest> captor =
                ArgumentCaptor.forClass(BiometricMicroserviceRequest.class);
        verify(microserviceClient).createVerification(captor.capture());
        BiometricMicroserviceRequest sent = captor.getValue();
        assertThat(sent.subjectIdNumber()).isEqualTo("39289507");
        assertThat(sent.workstationId()).isEqualTo("WS-NRB-014");
        assertThat(sent.verificationRequestId()).isEqualTo(response.id().toString());
        verify(repository, org.mockito.Mockito.times(2)).save(any(BiometricVerification.class));
    }

    @Test
    void handleCallbackResolvesPendingVerificationAndPublishesEvent() {
        BiometricVerification verification = pendingVerification();
        verification.setEkycRequestId("ekyc-req-1");
        when(repository.findByEkycRequestId("ekyc-req-1")).thenReturn(Optional.of(verification));
        when(repository.save(any(BiometricVerification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.handleCallback(new BiometricCallbackPayload("ekyc-req-1", "accepted", "match", "DICP2000", 3));

        assertThat(verification.getStatus()).isEqualTo(BiometricVerificationStatus.ACCEPTED);
        assertThat(verification.getResult()).isEqualTo("match");
        assertThat(verification.getRemainingAttempts()).isEqualTo(3);
        verify(eventPublisher).publish(org.mockito.ArgumentMatchers.eq("biometric-verification.resolved"),
                org.mockito.ArgumentMatchers.any(java.util.Map.class));
    }

    @Test
    void handleCallbackIgnoresDuplicateCallback() {
        BiometricVerification verification = pendingVerification();
        verification.setEkycRequestId("ekyc-req-1");
        verification.setStatus(BiometricVerificationStatus.ACCEPTED);
        when(repository.findByEkycRequestId("ekyc-req-1")).thenReturn(Optional.of(verification));

        service.handleCallback(new BiometricCallbackPayload("ekyc-req-1", "accepted", "match", "DICP2000", 3));

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any(), any());
    }

    @Test
    void handleCallbackThrowsForUnknownEkYcRequestId() {
        when(repository.findByEkycRequestId("unknown-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleCallback(
                new BiometricCallbackPayload("unknown-id", "accepted", "match", "DICP2000", 3)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void resendProxiesToMicroserviceWhenPending() {
        BiometricVerification verification = pendingVerification();
        verification.setEkycRequestId("ekyc-req-1");
        when(repository.findById(verification.getId())).thenReturn(Optional.of(verification));
        when(microserviceClient.resendCallback("ekyc-req-1")).thenReturn(HttpStatusCode.valueOf(200));

        HttpStatusCode status = service.resend(verification.getId());

        assertThat(status.value()).isEqualTo(200);
        verify(microserviceClient).resendCallback("ekyc-req-1");
    }

    @Test
    void resendRejectsResolvedVerification() {
        BiometricVerification verification = pendingVerification();
        verification.setStatus(BiometricVerificationStatus.ACCEPTED);
        when(repository.findById(verification.getId())).thenReturn(Optional.of(verification));

        assertThatThrownBy(() -> service.resend(verification.getId()))
                .isInstanceOf(IllegalStateException.class);
        verify(microserviceClient, never()).resendCallback(any());
    }

    @Test
    void resendRejectsVerificationWithoutEkYcRequestId() {
        BiometricVerification verification = pendingVerification();
        when(repository.findById(verification.getId())).thenReturn(Optional.of(verification));

        assertThatThrownBy(() -> service.resend(verification.getId()))
                .isInstanceOf(IllegalStateException.class);
        verify(microserviceClient, never()).resendCallback(any());
    }

    private BiometricVerification pendingVerification() {
        BiometricVerification verification = new BiometricVerification();
        verification.setId(UUID.randomUUID());
        verification.setSubjectIdNumber("39289507");
        verification.setSubjectIdType("citizen");
        verification.setPolicyNumber("VMI-POL-001");
        verification.setWorkstationId("WS-NRB-014");
        verification.setStatus(BiometricVerificationStatus.PENDING);
        return verification;
    }
}
