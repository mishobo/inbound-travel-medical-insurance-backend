package com.travel.insurance.procedure.upload.dto;

import com.travel.insurance.procedure.upload.ProcedureUploadStatus;

import java.time.Instant;
import java.util.UUID;

public record ProcedureUploadResponse(
        UUID uploadPublicId,
        String originalFilename,
        ProcedureUploadStatus status,
        int totalRows,
        int validRows,
        int createdRows,
        int skippedRows,
        int failedRows,
        UUID uploadedBy,
        Instant uploadTime,
        Instant processingStartTime,
        Instant completionTime,
        String failureReason
) {
}
