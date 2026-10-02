package com.pesaguard.backend.environment.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.domain.ProjectEnvironment;

public interface ProjectEnvironmentRepository extends JpaRepository<ProjectEnvironment, UUID> {

    Optional<ProjectEnvironment> findByIdAndOrganizationIdAndProjectId(
            UUID id, UUID organizationId, UUID projectId);

    Optional<ProjectEnvironment> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<ProjectEnvironment> findByOrganizationIdAndProjectIdOrderByCreatedAtAsc(
            UUID organizationId, UUID projectId);

    boolean existsByProjectIdAndName(UUID projectId, String name);

    boolean existsByProjectIdAndType(UUID projectId, EnvironmentType type);

    Optional<ProjectEnvironment> findByProjectIdAndType(UUID projectId, EnvironmentType type);

    long countByProjectId(UUID projectId);
}
