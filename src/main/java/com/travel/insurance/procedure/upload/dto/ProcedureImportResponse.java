package com.travel.insurance.procedure.upload.dto;

import com.travel.insurance.procedure.upload.ProcedureUploadStatus;

import java.util.List;
import java.util.UUID;

public record ProcedureImportResponse(
        UUID uploadPublicId,
        ProcedureUploadStatus status,
        int totalRows,
        int createdRows,
        int skippedRows,
        int failedRows,
        List<ProcedureUploadRowResult> rows
) {
}
