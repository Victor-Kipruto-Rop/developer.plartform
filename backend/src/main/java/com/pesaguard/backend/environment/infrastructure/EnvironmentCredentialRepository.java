package com.pesaguard.backend.environment.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.environment.domain.EnvironmentCredential;
import com.pesaguard.backend.environment.domain.EnvironmentCredentialStatus;

public interface EnvironmentCredentialRepository extends JpaRepository<EnvironmentCredential, UUID> {

    List<EnvironmentCredential> findByEnvironmentIdOrderByCreatedAtDesc(UUID environmentId);

    long countByEnvironmentIdAndStatus(UUID environmentId, EnvironmentCredentialStatus status);

    long countByEnvironmentIdAndNameAndStatus(
            UUID environmentId, String name, EnvironmentCredentialStatus status);

    List<EnvironmentCredential> findByEnvironmentIdAndNameOrderByVersionDesc(UUID environmentId, String name);
}