package com.pesaguard.backend.credentials.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<ApiKey> findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
            UUID organizationId, UUID projectId, UUID environmentId);

    long countByEnvironmentIdAndStatus(UUID environmentId, ApiKeyStatus status);

    Optional<ApiKey> findBySecretHash(String secretHash);

    Optional<ApiKey> findByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
            UUID id, UUID organizationId, UUID projectId, UUID environmentId);
}
