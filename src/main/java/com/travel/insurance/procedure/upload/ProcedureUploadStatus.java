package com.travel.insurance.procedure.upload;

public enum ProcedureUploadStatus {
    RECEIVED,
    VALIDATING,
    VALIDATION_FAILED,
    READY_FOR_IMPORT,
    PROCESSING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED
}
