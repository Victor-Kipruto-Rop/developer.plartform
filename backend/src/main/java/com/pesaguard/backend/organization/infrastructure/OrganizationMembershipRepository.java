package com.pesaguard.backend.organization.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.organization.domain.OrganizationMembership;

public interface OrganizationMembershipRepository extends JpaRepository<OrganizationMembership, UUID> {

    @Query("""
            select membership from OrganizationMembership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where user.email = :email
              and membership.status = com.pesaguard.backend.organization.domain.MembershipStatus.ACTIVE
              and organization.status in (
                  com.pesaguard.backend.organization.domain.OrganizationStatus.ACTIVE,
                  com.pesaguard.backend.organization.domain.OrganizationStatus.SUSPENDED)
              and user.status = com.pesaguard.backend.member.domain.UserStatus.ACTIVE
            order by organization.name asc
            """)
    List<OrganizationMembership> findAllLoginableByEmail(@Param("email") String email);

    @Query("""
            select membership from OrganizationMembership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where user.email = :email
              and membership.organization.id = :organizationId
              and membership.status = com.pesaguard.backend.organization.domain.MembershipStatus.ACTIVE
              and organization.status in (
                  com.pesaguard.backend.organization.domain.OrganizationStatus.ACTIVE,
                  com.pesaguard.backend.organization.domain.OrganizationStatus.SUSPENDED)
              and user.status = com.pesaguard.backend.member.domain.UserStatus.ACTIVE
            """)
    Optional<OrganizationMembership> findLoginableByEmailAndOrganizationId(
            @Param("email") String email, @Param("organizationId") UUID organizationId);

    @Query("""
            select membership from OrganizationMembership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where membership.organization.id = :organizationId
            order by membership.createdAt asc
            """)
    List<OrganizationMembership> findAllByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("""
            select membership from OrganizationMembership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where membership.id = :membershipId
              and membership.organization.id = :organizationId
            """)
    Optional<OrganizationMembership> findByIdAndOrganizationId(
            @Param("membershipId") UUID membershipId,
            @Param("organizationId") UUID organizationId);

    @Query("""
            select membership from OrganizationMembership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where membership.organization.id = :organizationId
              and membership.user.id = :userId
            """)
    Optional<OrganizationMembership> findByOrganizationIdAndUserId(
            @Param("organizationId") UUID organizationId,
            @Param("userId") UUID userId);

    default List<OrganizationMembership> findAllActiveByEmail(String email) {
        return findAllLoginableByEmail(email).stream()
                .filter(membership -> membership.getOrganization().isActive())
                .toList();
    }

    boolean existsByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    @Query("""
            select count(membership) > 0 from OrganizationMembership membership
            where membership.organization.id = :organizationId
              and lower(membership.user.email) = lower(:email)
            """)
    boolean existsByOrganizationIdAndUserEmailIgnoreCase(
            @Param("organizationId") UUID organizationId, @Param("email") String email);
}
