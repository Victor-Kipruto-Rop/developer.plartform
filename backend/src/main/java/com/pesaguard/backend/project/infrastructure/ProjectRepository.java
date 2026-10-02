package com.pesaguard.backend.project.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.project.domain.Project;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Project> findByOrganizationIdAndSlug(UUID organizationId, String slug);

    Page<Project> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    boolean existsByOrganizationIdAndSlug(UUID organizationId, String slug);
}
