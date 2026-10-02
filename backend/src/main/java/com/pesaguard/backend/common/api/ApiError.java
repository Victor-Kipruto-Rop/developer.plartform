package com.pesaguard.backend.common.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ApiError(
        @NotBlank String code,
        @NotBlank String message,
        @NotNull UUID requestId,
        @NotNull Instant timestamp,
        List<FieldViolation> violations) {

    public ApiError {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }
}
