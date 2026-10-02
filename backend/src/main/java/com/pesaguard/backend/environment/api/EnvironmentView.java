package com.pesaguard.backend.environment.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.pesaguard.backend.environment.domain.EnvironmentStatus;
import com.pesaguard.backend.environment.domain.EnvironmentType;

public record EnvironmentView(
        UUID id,
        UUID projectId,
        String name,
        EnvironmentType type,
        EnvironmentStatus status,
        Map<String, Object> configuration,
        Instant statusChangedAt,
        Instant createdAt,
        Instant updatedAt) {

    public EnvironmentView {
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
    }
}
