package com.pesaguard.backend.security.sessions;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    @Query("""
            select session from AuthSession session
            join fetch session.membership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where session.tokenHash = :tokenHash
              and session.revokedAt is null
              and session.expiresAt > :now
            """)
    Optional<AuthSession> findActiveByTokenHash(
            @Param("tokenHash") String tokenHash,
            @Param("now") Instant now);

    Optional<AuthSession> findByIdAndMembershipOrganizationId(UUID id, UUID organizationId);

    @Query("""
            select count(session) from AuthSession session
            where session.membership.id = :membershipId
              and session.revokedAt is null
              and session.expiresAt > :now
            """)
    long countActiveByMembershipIdAndNow(@Param("membershipId") UUID membershipId, @Param("now") Instant now);

    @Query("""
            select session from AuthSession session
            where session.membership.id = :membershipId
              and session.revokedAt is null
              and session.expiresAt > :now
            order by session.lastSeenAt asc
            """)
    List<AuthSession> findActiveByMembershipIdOrderByLastSeenAtAsc(
            @Param("membershipId") UUID membershipId, @Param("now") Instant now);

    @Modifying
    @Query("update AuthSession session set session.revokedAt = :now "
            + "where session.membership.organization.id = :organizationId "
            + "and session.revokedAt is null")
    int revokeActiveByOrganizationId(@Param("organizationId") UUID organizationId, @Param("now") Instant now);

    @Modifying
    @Query("update AuthSession session set session.revokedAt = :now "
            + "where session.membership.id = :membershipId and session.revokedAt is null")
    int revokeActiveByMembershipId(@Param("membershipId") UUID membershipId, @Param("now") Instant now);
}
