package com.travel.insurance.procedure.upload;

import com.travel.insurance.procedure.upload.dto.ProcedureImportResponse;
import com.travel.insurance.procedure.upload.dto.ProcedureUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface ProcedureUploadService {

    ProcedureImportResponse upload(MultipartFile file);

    ProcedureUploadResponse getUpload(UUID uploadPublicId);

    byte[] template();

    byte[] errorReport(UUID uploadPublicId);
}
