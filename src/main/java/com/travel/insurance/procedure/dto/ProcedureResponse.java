package com.travel.insurance.procedure.dto;

import java.time.Instant;
import java.util.UUID;

public record ProcedureResponse(
        UUID id,
        String procedureCode,
        String name,
        String description,
        UUID departmentPublicId,
        boolean active,
        UUID uploadBatchPublicId,
        Instant createdDate,
        Instant updatedDate
) {
}
