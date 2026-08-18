package com.travel.insurance.insurer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.insurance.auth.JwtTokenProvider;
import com.travel.insurance.insurer.dto.InsurerRequest;
import com.travel.insurance.insurer.dto.InsurerResponse;
import com.travel.insurance.policy.PolicyService;
import com.travel.insurance.policy.PolicyStatus;
import com.travel.insurance.policy.PolicyType;
import com.travel.insurance.policy.dto.PolicyResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InsurerController.class)
class InsurerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private InsurerService insurerService;

    @MockBean
    private PolicyService policyService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private final UUID insurerId = UUID.randomUUID();

    private InsurerResponse sampleResponse() {
        return new InsurerResponse(insurerId, "Acme Insurance", "contact@acme.example",
                null, null, "https://cdn.example/acme.png", 42L, 42L, Instant.now(), Instant.now());
    }

    private PolicyResponse samplePolicy() {
        return new PolicyResponse(UUID.randomUUID(), "POL-0001", insurerId,
                PolicyType.SINGLE_ENTRY_UP_TO_30_DAYS, PolicyStatus.ACTIVE, Instant.now(), Instant.now());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getByIdReturnsInsurer() throws Exception {
        when(insurerService.getById(insurerId)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/insurers/{id}", insurerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(insurerId.toString()))
                .andExpect(jsonPath("$.name").value("Acme Insurance"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createReturnsCreated() throws Exception {
        when(insurerService.create(any(InsurerRequest.class))).thenReturn(sampleResponse());

        InsurerRequest request = new InsurerRequest("Acme Insurance", "contact@acme.example", null, null,
                "https://cdn.example/acme.png", 42L);
        mockMvc.perform(post("/api/v1/insurers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Acme Insurance"))
                .andExpect(jsonPath("$.logoUrl").value("https://cdn.example/acme.png"))
                .andExpect(jsonPath("$.policyToken").value(42));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createRejectsInvalidBody() throws Exception {
        InsurerRequest request = new InsurerRequest("", "not-an-email", null, null, null, null);
        mockMvc.perform(post("/api/v1/insurers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listDetailedEmbedsPoliciesPerInsurer() throws Exception {
        when(insurerService.listAll()).thenReturn(List.of(sampleResponse()));
        when(policyService.listByInsurerId(insurerId)).thenReturn(List.of(samplePolicy()));

        mockMvc.perform(get("/api/v1/insurers/detailed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(insurerId.toString()))
                .andExpect(jsonPath("$[0].name").value("Acme Insurance"))
                .andExpect(jsonPath("$[0].policies[0].policyNumber").value("POL-0001"));
    }

    @Test
    void getWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/insurers/{id}", insurerId))
                .andExpect(status().isUnauthorized());
    }
}
