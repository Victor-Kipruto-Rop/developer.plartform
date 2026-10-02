package com.pesaguard.backend.project.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.project.domain.ProjectMemberRole;
import com.pesaguard.backend.project.infrastructure.ProjectMemberRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Project authorization.
 *
 * <p>Organization OWNER and ADMIN roles implicitly manage every project in the
 * organization. Everyone else needs an explicit, active project membership with
 * a sufficient role. A revoked membership grants nothing, even if the underlying
 * organization role would otherwise qualify.
 */
@Component
public class ProjectAuthorization {

    private final ProjectMemberRepository memberRepository;

    public ProjectAuthorization(ProjectMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    public void requireProjectManage(AuthenticatedUser principal, UUID projectId) {
        if (isOrganizationManager(principal)) {
            return;
        }
        if (memberRepository.findActiveByProjectIdAndUserIdAndRoleIn(
                        projectId, principal.userId(),
                        List.of(ProjectMemberRole.MANAGER, ProjectMemberRole.DEVELOPER))
                .isEmpty()) {
            throw denied();
        }
    }

    public void requireProjectRead(AuthenticatedUser principal, UUID projectId) {
        if (isOrganizationManager(principal)) {
            return;
        }
        if (memberRepository.findByProjectIdAndUserId(projectId, principal.userId())
                .filter(member -> member.isActive())
                .isEmpty()) {
            throw denied();
        }
    }

    public boolean isOrganizationManager(AuthenticatedUser principal) {
        return principal.authorities().stream()
                .map(String::valueOf)
                .map(authority -> authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority)
                .map(role -> {
                    try {
                        return OrganizationRole.valueOf(role);
                    } catch (IllegalArgumentException exception) {
                        return null;
                    }
                })
                .filter(Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN)::contains)
                .findFirst()
                .isPresent();
    }

    public ProjectMemberRole effectiveRole(AuthenticatedUser principal, UUID projectId) {
        return memberRepository.findByProjectIdAndUserId(projectId, principal.userId())
                .filter(member -> member.isActive())
                .map(member -> member.getRole())
                .orElse(null);
    }

    private BusinessException denied() {
        return new BusinessException(
                org.springframework.http.HttpStatus.FORBIDDEN,
                "PROJECT_ACCESS_DENIED",
                "You do not have access to this project.");
    }
}