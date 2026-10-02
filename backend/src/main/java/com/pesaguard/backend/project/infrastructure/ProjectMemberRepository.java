package com.pesaguard.backend.project.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.project.domain.ProjectMember;
import com.pesaguard.backend.project.domain.ProjectMemberRole;
import com.pesaguard.backend.project.domain.ProjectMemberStatus;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

    Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

    Optional<ProjectMember> findByIdAndProjectId(UUID id, UUID projectId);

    List<ProjectMember> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    @Query("""
            select member from ProjectMember member
            where member.organizationId = :organizationId
              and member.userId = :userId
              and member.status = com.pesaguard.backend.project.domain.ProjectMemberStatus.ACTIVE
            """)
    List<ProjectMember> findActiveByOrganizationIdAndUserId(
            @Param("organizationId") UUID organizationId, @Param("userId") UUID userId);

    @Query("""
            select member from ProjectMember member
            where member.projectId = :projectId
              and member.userId = :userId
              and member.status = com.pesaguard.backend.project.domain.ProjectMemberStatus.ACTIVE
              and member.role in :roles
            """)
    List<ProjectMember> findActiveByProjectIdAndUserIdAndRoleIn(
            @Param("projectId") UUID projectId,
            @Param("userId") UUID userId,
            @Param("roles") List<ProjectMemberRole> roles);

    boolean existsByProjectIdAndUserIdAndStatus(
            UUID projectId, UUID userId, ProjectMemberStatus status);
}