package com.pesaguard.backend.rbac.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.rbac.domain.ProductionAccessRequest;
import com.pesaguard.backend.rbac.domain.ProductionAccessStatus;

public interface ProductionAccessRequestRepository
        extends JpaRepository<ProductionAccessRequest, UUID> {

    List<ProductionAccessRequest> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<ProductionAccessRequest> findByOrganizationIdAndStatusOrderByCreatedAtDesc(
            UUID organizationId, ProductionAccessStatus status);

    Optional<ProductionAccessRequest> findByIdAndOrganizationId(UUID id, UUID organizationId);

    long countByOrganizationIdAndStatus(UUID organizationId, ProductionAccessStatus status);
}