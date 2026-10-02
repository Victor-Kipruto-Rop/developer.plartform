package com.pesaguard.backend.security.principals;

import java.util.Set;
import java.util.UUID;

import com.pesaguard.backend.organization.domain.OrganizationStatus;

public record AuthenticatedUser(
        UUID userId,
        UUID organizationId,
        UUID sessionId,
        String email,
        String displayName,
        Set<String> authorities,
        OrganizationStatus organizationStatus) {

    public AuthenticatedUser {
        authorities = Set.copyOf(authorities);
    }

    public AuthenticatedUser(
            UUID userId,
            UUID organizationId,
            UUID sessionId,
            String email,
            String displayName,
            Set<String> authorities) {
        this(userId, organizationId, sessionId, email, displayName, authorities, OrganizationStatus.ACTIVE);
    }

    public boolean organizationActive() {
        return organizationStatus == OrganizationStatus.ACTIVE;
    }
}
