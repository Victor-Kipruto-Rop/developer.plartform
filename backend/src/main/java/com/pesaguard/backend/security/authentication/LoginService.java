package com.pesaguard.backend.security.authentication;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.UnauthorizedException;
import com.pesaguard.backend.config.ApplicationProperties;
import com.pesaguard.backend.organization.application.OrganizationSecuritySettingsService;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.domain.OrganizationSecuritySettings;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.security.sessions.SessionService;
import com.pesaguard.backend.security.throttling.RequestThrottleService;

@Service
public class LoginService {

    private final OrganizationMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;
    private final RequestThrottleService throttleService;
    private final OrganizationSecuritySettingsService securitySettingsService;
    private final PasswordPolicy passwordPolicy;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final ApplicationProperties properties;
    private final SecretKey credentialKey;

    public LoginService(
            OrganizationMembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            CredentialCryptoService credentialCryptoService,
            RequestThrottleService throttleService,
            OrganizationSecuritySettingsService securitySettingsService,
            PasswordPolicy passwordPolicy,
            SessionService sessionService,
            AuditService auditService,
            ApplicationProperties properties,
            @Qualifier("credentialHmacKey") SecretKey credentialKey) {
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.dummyPasswordHash = passwordEncoder.encode(credentialCryptoService.randomToken(32));
        this.throttleService = throttleService;
        this.securitySettingsService = securitySettingsService;
        this.passwordPolicy = passwordPolicy;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.properties = properties;
        this.credentialKey = credentialKey;
    }

    @Transactional
    public AuthenticationResponse login(LoginRequest request, String remoteAddress) {
        String email = RegistrationService.normalizeEmail(request.email());
        String accountHash = subjectHash("account", email);
        String ipHash = subjectHash("ip", remoteAddress == null ? "unknown" : remoteAddress);
        passwordPolicy.validateMaximumLength(request.password());
        throttleService.assertAllowed("account", accountHash, properties.security().accountLoginFailureLimit());
        throttleService.assertAllowed("ip", ipHash, properties.security().ipLoginFailureLimit());

        List<OrganizationMembership> candidates = membershipRepository.findAllActiveByEmail(email);
        OrganizationMembership membership = resolveMembership(candidates, request.organizationId());
        String hash = membership == null ? dummyPasswordHash : membership.getUser().getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (membership == null || !passwordMatches || !membership.isActive()) {
            throttleService.recordFailure("account", accountHash, properties.security().accountLoginFailureLimit());
            throttleService.recordFailure("ip", ipHash, properties.security().ipLoginFailureLimit());
            throw invalidCredentials();
        }

        throttleService.clear("account", accountHash);
        OrganizationSecuritySettings settings =
                securitySettingsService.getOrCreate(membership.getOrganization().getId());
        securitySettingsService.assertPasswordLoginAllowed(settings);
        securitySettingsService.assertIpAllowed(settings, remoteAddress);
        SessionService.IssuedSession session = sessionService.issue(membership,
                Duration.ofMinutes(settings.getSessionTtlMinutes()), settings.getMaxSessions());
        auditService.append(
                membership.getOrganization().getId(), membership.getUser().getId(), "auth.login.succeeded",
                "session", session.sessionId().toString(), RequestContext.currentRequestId(), Map.of());
        return response(membership, session);
    }

    @Transactional
    public void logout(UUID userId, UUID organizationId, UUID sessionId) {
        AuthenticatedUser principal = new AuthenticatedUser(
                userId, organizationId, sessionId, "", "", Set.of());
        if (sessionService.revoke(principal)) {
            auditService.append(
                    organizationId, userId, "auth.logout", "session", sessionId.toString(),
                    RequestContext.currentRequestId(), Map.of());
        }
    }

    private OrganizationMembership resolveMembership(
            List<OrganizationMembership> candidates, UUID requestedOrganizationId) {
        if (requestedOrganizationId != null) {
            return candidates.stream()
                    .filter(candidate -> candidate.getOrganization().getId().equals(requestedOrganizationId))
                    .findFirst()
                    .orElse(null);
        }
        if (candidates.size() > 1) {
            throw organizationSelectionRequired();
        }
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private BusinessException organizationSelectionRequired() {
        return new BusinessException(HttpStatus.CONFLICT, "ORGANIZATION_SELECTION_REQUIRED",
                "This account belongs to more than one organization. Select the organization to sign in to.");
    }

    private AuthenticationResponse response(
            OrganizationMembership membership,
            SessionService.IssuedSession session) {
        return new AuthenticationResponse(
                session.token(), "Bearer", session.expiresAt(),
                new SessionUserResponse(
                        membership.getUser().getId(),
                        membership.getUser().getEmail(),
                        membership.getUser().getDisplayName()),
                new SessionOrganizationResponse(
                        membership.getOrganization().getId(),
                        membership.getOrganization().getName(),
                        membership.getOrganization().getSlug()));
    }

    private String subjectHash(String type, String value) {
        return CredentialCryptoService.hmacSha256(credentialKey, "login-throttle:" + type + ":" + value);
    }

    private UnauthorizedException invalidCredentials() {
        return new UnauthorizedException("INVALID_CREDENTIALS", "The email or password is incorrect.");
    }
}
