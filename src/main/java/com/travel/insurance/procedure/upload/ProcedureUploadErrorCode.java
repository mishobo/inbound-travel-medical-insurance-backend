package com.travel.insurance.procedure.upload;

public enum ProcedureUploadErrorCode {
    NAME_REQUIRED,
    NAME_TOO_LONG,
    DEPARTMENT_REQUIRED,
    DEPARTMENT_NOT_FOUND,
    DUPLICATE_IN_FILE,
    ALREADY_EXISTS,
    INACTIVE_EXISTS,
    DB_CONFLICT
}
