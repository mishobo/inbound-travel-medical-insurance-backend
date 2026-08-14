package com.travel.insurance.procedure.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ProcedureRequest(
        @NotBlank String name,
        String description,
        @NotNull UUID departmentPublicId
) {
}
