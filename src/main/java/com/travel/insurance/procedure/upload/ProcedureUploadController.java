package com.travel.insurance.procedure.upload;

import com.travel.insurance.procedure.upload.dto.ProcedureImportResponse;
import com.travel.insurance.procedure.upload.dto.ProcedureUploadResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/procedures")
@RequiredArgsConstructor
public class ProcedureUploadController {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ProcedureUploadService procedureUploadService;

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProcedureImportResponse> upload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(procedureUploadService.upload(file));
    }

    @GetMapping("/upload/{uploadPublicId}")
    public ResponseEntity<ProcedureUploadResponse> getUpload(@PathVariable UUID uploadPublicId) {
        return ResponseEntity.ok(procedureUploadService.getUpload(uploadPublicId));
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> downloadTemplate() {
        return responseEntity(procedureUploadService.template(), "procedure-upload-template.xlsx");
    }

    @GetMapping("/upload/{uploadPublicId}/errors")
    public ResponseEntity<byte[]> downloadErrorReport(@PathVariable UUID uploadPublicId) {
        return responseEntity(procedureUploadService.errorReport(uploadPublicId),
                "procedure-upload-errors-" + uploadPublicId + ".xlsx");
    }

    private ResponseEntity<byte[]> responseEntity(byte[] body, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, XLSX_CONTENT_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }
}
