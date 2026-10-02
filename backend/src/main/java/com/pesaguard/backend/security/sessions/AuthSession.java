package com.pesaguard.backend.security.sessions;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.pesaguard.backend.organization.domain.OrganizationMembership;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "auth_sessions")
public class AuthSession {

    @Id
    private UUID id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "membership_id", nullable = false)
    private OrganizationMembership membership;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuthSession() {
    }

    private AuthSession(UUID id, String tokenHash, OrganizationMembership membership, Instant now, Instant expiresAt) {
        this.id = id;
        this.tokenHash = tokenHash;
        this.membership = membership;
        this.expiresAt = expiresAt;
        this.lastSeenAt = now;
    }

    public static AuthSession create(String tokenHash, OrganizationMembership membership, Instant now, Instant expiresAt) {
        return new AuthSession(UUID.randomUUID(), tokenHash, membership, now, expiresAt);
    }

    public boolean isActive(Instant now) {
        return isActive(now, null);
    }

    public boolean isActive(Instant now, java.time.Duration idleTimeout) {
        boolean withinIdleWindow = idleTimeout == null
                || lastSeenAt.plus(idleTimeout).isAfter(now);
        return revokedAt == null && expiresAt.isAfter(now) && withinIdleWindow
                && membership.isActive() && membership.getOrganization().isActive();
    }

    public void touch(Instant now) {
        lastSeenAt = now;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public OrganizationMembership getMembership() {
        return membership;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
