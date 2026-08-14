package com.travel.insurance.procedure.upload.dto;

import com.travel.insurance.procedure.upload.ProcedureRowStatus;

import java.util.UUID;

public record ProcedureUploadRowResult(
        int excelRowNumber,
        String name,
        String department,
        String description,
        ProcedureRowStatus status,
        String errorCode,
        String errorMessage,
        UUID createdProcedurePublicId
) {
}
