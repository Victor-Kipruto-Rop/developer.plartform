package com.pesaguard.backend.securitycenter.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.securitycenter.domain.SecurityEvent.Resolution;

public interface SecurityEventRepository extends JpaRepository<SecurityEventEntity, UUID> {

    /**
     * Every lookup is organization-scoped.
     *
     * <p>Never expose a find-by-id alone: an id is not a secret, and an unscoped
     * lookup would let one tenant read another's security signals by guessing a
     * UUID. A miss returns empty rather than another tenant's row.
     */
    Optional<SecurityEventEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<SecurityEventEntity> findByOrganizationIdAndResolutionOrderByDetectedAtDesc(
            UUID organizationId, Resolution resolution);

    List<SecurityEventEntity> findByOrganizationIdOrderByDetectedAtDesc(UUID organizationId);

    List<SecurityEventEntity> findByOrganizationIdAndTypeOrderByDetectedAtDesc(
            UUID organizationId, com.pesaguard.backend.securitycenter.domain.SecurityEventType type);

    long countByOrganizationIdAndResolution(UUID organizationId, Resolution resolution);
}