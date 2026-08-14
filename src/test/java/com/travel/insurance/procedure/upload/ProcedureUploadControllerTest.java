package com.travel.insurance.procedure.upload;

import com.travel.insurance.auth.JwtTokenProvider;
import com.travel.insurance.procedure.upload.dto.ProcedureImportResponse;
import com.travel.insurance.procedure.upload.dto.ProcedureUploadResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProcedureUploadController.class)
class ProcedureUploadControllerTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProcedureUploadService procedureUploadService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @WithMockUser
    void uploadCreatesProceduresAndReturnsSummary() throws Exception {
        when(procedureUploadService.upload(any())).thenReturn(
                new ProcedureImportResponse(UUID.randomUUID(), ProcedureUploadStatus.COMPLETED,
                        3, 2, 1, 0, List.of()));
        MockMultipartFile file = new MockMultipartFile("file", "procedures.xlsx", XLSX, new byte[]{1});

        mockMvc.perform(multipart("/api/v1/procedures/upload").file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.createdRows").value(2))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @WithMockUser
    void getUploadReturnsStatus() throws Exception {
        UUID uploadId = UUID.randomUUID();
        when(procedureUploadService.getUpload(uploadId)).thenReturn(new ProcedureUploadResponse(
                uploadId, "procedures.xlsx", ProcedureUploadStatus.COMPLETED,
                2, 2, 2, 0, 0, UUID.randomUUID(), Instant.now(), Instant.now(), Instant.now(), null));

        mockMvc.perform(get("/api/v1/procedures/upload/{id}", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @WithMockUser
    void downloadTemplateReturnsXlsxAttachment() throws Exception {
        when(procedureUploadService.template()).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/api/v1/procedures/upload/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", XLSX))
                .andExpect(header().string("Content-Disposition",
                        containsString("procedure-upload-template.xlsx")));
    }

    @Test
    @WithMockUser
    void downloadErrorReportUsesTraceableFilename() throws Exception {
        UUID uploadId = UUID.randomUUID();
        when(procedureUploadService.errorReport(uploadId)).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/api/v1/procedures/upload/{id}/errors", uploadId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("procedure-upload-errors-" + uploadId + ".xlsx")));
    }

    @Test
    void validateWithoutAuthenticationIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "procedures.xlsx", XLSX, new byte[]{1});

        mockMvc.perform(multipart("/api/v1/procedures/upload").file(file).with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
