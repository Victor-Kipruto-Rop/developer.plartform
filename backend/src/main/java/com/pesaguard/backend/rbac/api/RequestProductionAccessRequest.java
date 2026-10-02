package com.pesaguard.backend.rbac.api;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RequestProductionAccessRequest(
        @NotNull UUID environmentId,
        @NotBlank @Size(min = 10, max = 1000) String reason) {
}