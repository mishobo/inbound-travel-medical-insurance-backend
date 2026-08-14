package com.travel.insurance.procedure.upload;

import com.travel.insurance.procedure.upload.dto.ProcedureImportResponse;
import com.travel.insurance.procedure.upload.dto.ProcedureUploadResponse;
import com.travel.insurance.procedure.upload.dto.ProcedureUploadRowResult;
import com.travel.insurance.procedure.upload.dto.ProcedureUploadValidationResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProcedureUploadMapper {

    public ProcedureUploadRowResult toRowResult(ProcedureUploadRow row) {
        return new ProcedureUploadRowResult(
                row.getExcelRowNumber(),
                row.getProcedureName(),
                row.getDepartment(),
                row.getDescription(),
                row.getRowStatus(),
                row.getErrorCode(),
                row.getErrorMessage(),
                row.getProcedurePublicId()
        );
    }

    public ProcedureUploadValidationResponse toValidationResponse(
            ProcedureUpload upload, List<ProcedureUploadRow> rows) {
        return new ProcedureUploadValidationResponse(
                upload.getId(),
                upload.getStatus(),
                upload.getTotalRows(),
                upload.getValidRows(),
                upload.getSkippedRows(),
                upload.getFailedRows(),
                upload.getStatus() == ProcedureUploadStatus.READY_FOR_IMPORT,
                rows.stream().map(this::toRowResult).toList()
        );
    }

    public ProcedureImportResponse toImportResponse(ProcedureUpload upload, List<ProcedureUploadRow> rows) {
        return new ProcedureImportResponse(
                upload.getId(),
                upload.getStatus(),
                upload.getTotalRows(),
                upload.getCreatedRows(),
                upload.getSkippedRows(),
                upload.getFailedRows(),
                rows.stream().map(this::toRowResult).toList()
        );
    }

    public ProcedureUploadResponse toUploadResponse(ProcedureUpload upload) {
        return new ProcedureUploadResponse(
                upload.getId(),
                upload.getOriginalFilename(),
                upload.getStatus(),
                upload.getTotalRows(),
                upload.getValidRows(),
                upload.getCreatedRows(),
                upload.getSkippedRows(),
                upload.getFailedRows(),
                upload.getUploadedBy(),
                upload.getCreatedDate(),
                upload.getProcessingStartTime(),
                upload.getCompletionTime(),
                upload.getFailureReason()
        );
    }
}
