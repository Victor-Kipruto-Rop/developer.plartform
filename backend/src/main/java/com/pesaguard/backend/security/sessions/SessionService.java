package com.pesaguard.backend.security.sessions;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.config.ApplicationProperties;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@Service
public class SessionService {

    private final AuthSessionRepository sessionRepository;
    private final CredentialCryptoService credentialCryptoService;
    private final ApplicationProperties properties;
    private final Clock clock;

    public SessionService(
            AuthSessionRepository sessionRepository,
            CredentialCryptoService credentialCryptoService,
            ApplicationProperties properties,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.credentialCryptoService = credentialCryptoService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public IssuedSession issue(OrganizationMembership membership) {
        Instant now = clock.instant();
        String token = credentialCryptoService.randomToken(32);
        AuthSession session = AuthSession.create(
                credentialCryptoService.sha256(token),
                membership,
                now,
                now.plus(properties.security().sessionTtl()));
        sessionRepository.save(session);
        return new IssuedSession(token, session.getId(), session.getExpiresAt());
    }

    @Transactional
    public IssuedSession issue(OrganizationMembership membership, java.time.Duration sessionTtl) {
        return issue(membership, sessionTtl, 0);
    }

    @Transactional
    public IssuedSession issue(
            OrganizationMembership membership, java.time.Duration sessionTtl, int maxSessions) {
        Instant now = clock.instant();
        enforceSessionLimit(membership.getId(), maxSessions, now);
        String token = credentialCryptoService.randomToken(32);
        AuthSession session = AuthSession.create(
                credentialCryptoService.sha256(token),
                membership,
                now,
                now.plus(sessionTtl));
        sessionRepository.save(session);
        return new IssuedSession(token, session.getId(), session.getExpiresAt());
    }

    private void enforceSessionLimit(UUID membershipId, int maxSessions, Instant now) {
        if (maxSessions <= 0) {
            return;
        }
        List<AuthSession> active = sessionRepository.findActiveByMembershipIdOrderByLastSeenAtAsc(
                membershipId, now);
        int excess = active.size() - (maxSessions - 1);
        for (int index = 0; index < excess; index++) {
            AuthSession session = active.get(index);
            session.revoke(now);
            sessionRepository.save(session);
        }
    }

    @Transactional
    public Optional<AuthenticatedUser> authenticate(String token) {
        Instant now = clock.instant();
        Optional<AuthSession> optionalSession = sessionRepository.findActiveByTokenHash(
                credentialCryptoService.sha256(token), now);
        if (optionalSession.isEmpty()) {
            return Optional.empty();
        }
        AuthSession session = optionalSession.get();
        if (!session.isActive(now)) {
            return Optional.empty();
        }
        OrganizationMembership membership = session.getMembership();
        session.touch(now);
        return Optional.of(toPrincipal(session, membership));
    }

    @Transactional
    public int revokeActiveByOrganizationId(UUID organizationId) {
        return sessionRepository.revokeActiveByOrganizationId(organizationId, clock.instant());
    }

    @Transactional
    public int revokeActiveByMembershipId(UUID membershipId) {
        return sessionRepository.revokeActiveByMembershipId(membershipId, clock.instant());
    }

    @Transactional
    public boolean revoke(AuthenticatedUser principal) {
        return sessionRepository.findByIdAndMembershipOrganizationId(
                        principal.sessionId(), principal.organizationId())
                .map(session -> {
                    boolean changed = session.getRevokedAt() == null;
                    session.revoke(clock.instant());
                    return changed;
                })
                .orElse(false);
    }

    private AuthenticatedUser toPrincipal(AuthSession session, OrganizationMembership membership) {
        Set<String> authorities = Set.of("ROLE_" + membership.getRole().name());
        return new AuthenticatedUser(
                membership.getUser().getId(),
                membership.getOrganization().getId(),
                session.getId(),
                membership.getUser().getEmail(),
                membership.getUser().getDisplayName(),
                authorities,
                membership.getOrganization().getStatus());
    }

    public record IssuedSession(String token, UUID sessionId, Instant expiresAt) {
    }
}
