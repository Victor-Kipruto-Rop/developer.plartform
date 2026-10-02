package com.pesaguard.backend.organization.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.organization.domain.InvitationStatus;
import com.pesaguard.backend.organization.domain.OrganizationInvitation;

public interface OrganizationInvitationRepository extends JpaRepository<OrganizationInvitation, UUID> {

    List<OrganizationInvitation> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<OrganizationInvitation> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<OrganizationInvitation> findByTokenHash(String tokenHash);

    boolean existsByOrganizationIdAndEmailIgnoreCaseAndStatus(
            UUID organizationId, String email, InvitationStatus status);
}