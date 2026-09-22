package com.travel.insurance.biometric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.insurance.auth.JwtTokenProvider;
import com.travel.insurance.biometric.dto.BiometricCallbackPayload;
import com.travel.insurance.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BiometricWebhookController.class)
@Import(SecurityConfig.class)
class BiometricWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BiometricVerificationService biometricVerificationService;

    @MockBean
    private MicroserviceWebhookSignatureVerifier webhookSignatureVerifier;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void acceptsValidCallback() throws Exception {
        when(webhookSignatureVerifier.isValid(anyString(), anyString(), anyString())).thenReturn(true);

        mockMvc.perform(signedPost())
                .andExpect(status().isOk());

        verify(biometricVerificationService).handleCallback(anyCallback());
    }

    @Test
    void rejectsCallbackWithInvalidSignature() throws Exception {
        when(webhookSignatureVerifier.isValid(anyString(), anyString(), anyString())).thenReturn(false);

        mockMvc.perform(signedPost())
                .andExpect(status().isUnauthorized());

        verify(biometricVerificationService, never()).handleCallback(anyCallback());
    }

    @Test
    void returnsInternalServerErrorWhenServiceFails() throws Exception {
        when(webhookSignatureVerifier.isValid(anyString(), anyString(), anyString())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("Database error"))
                .when(biometricVerificationService).handleCallback(anyCallback());

        mockMvc.perform(signedPost())
                .andExpect(status().isInternalServerError());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signedPost() throws Exception {
        return post("/api/v1/webhooks/biometric-verification")
                .header("x-webhook-timestamp", String.valueOf(Instant.now().getEpochSecond()))
                .header("x-webhook-signature", "deadbeef")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(samplePayload()));
    }

    private BiometricCallbackPayload anyCallback() {
        return org.mockito.ArgumentMatchers.any(BiometricCallbackPayload.class);
    }

    private BiometricCallbackPayload samplePayload() {
        return new BiometricCallbackPayload(
                "529fd955-0000-0000-0000-000000000001",
                "accepted",
                "match",
                "DICP2000",
                3);
    }
}
