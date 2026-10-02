package com.pesaguard.backend.security.authentication;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.ResourceConflictException;
import com.pesaguard.backend.config.ApplicationProperties;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.domain.OrganizationMembershipHistory;
import com.pesaguard.backend.organization.domain.OrganizationSecuritySettings;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipHistoryRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationSecuritySettingsRepository;
import com.pesaguard.backend.security.sessions.SessionService;

@Service
public class RegistrationService {

    private final UserAccountRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationSecuritySettingsRepository settingsRepository;
    private final OrganizationMembershipHistoryRepository historyRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final ApplicationProperties properties;
    private final Clock clock;

    public RegistrationService(
            UserAccountRepository userRepository,
            OrganizationRepository organizationRepository,
            OrganizationMembershipRepository membershipRepository,
            OrganizationSecuritySettingsRepository settingsRepository,
            OrganizationMembershipHistoryRepository historyRepository,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            SessionService sessionService,
            AuditService auditService,
            ApplicationProperties properties,
            Clock clock) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.settingsRepository = settingsRepository;
        this.historyRepository = historyRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public AuthenticationResponse register(RegisterRequest request) {
        if (!properties.security().registrationEnabled()) {
            throw new ResourceConflictException("REGISTRATION_DISABLED", "Public registration is disabled.");
        }
        String email = normalizeEmail(request.email());
        passwordPolicy.validate(request.password(), email);
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ResourceConflictException("EMAIL_ALREADY_REGISTERED", "An account already exists for this email.");
        }

        UserAccount user = userRepository.saveAndFlush(
                UserAccount.create(email, request.displayName().trim(), passwordEncoder.encode(request.password())));
        String organizationSlug = uniqueSlug(request.organizationName());
        Organization organization = organizationRepository.saveAndFlush(
                Organization.create(request.organizationName().trim(), organizationSlug, user.getId(),
                        clock.instant()));
        OrganizationMembership membership = membershipRepository.saveAndFlush(
                OrganizationMembership.owner(organization, user));
        settingsRepository.saveAndFlush(
                OrganizationSecuritySettings.defaults(organization.getId(), clock.instant()));
        historyRepository.save(
                OrganizationMembershipHistory.record(membership, null, null, user.getId(),
                        "organization.created", clock.instant()));

        SessionService.IssuedSession session = sessionService.issue(membership);
        auditService.append(
                organization.getId(),
                user.getId(),
                "organization.registered",
                "organization",
                organization.getId().toString(),
                RequestContext.currentRequestId(),
                Map.of("membershipId", membership.getId().toString()));

        return new AuthenticationResponse(
                session.token(),
                "Bearer",
                session.expiresAt(),
                new SessionUserResponse(user.getId(), user.getEmail(), user.getDisplayName()),
                new SessionOrganizationResponse(
                        organization.getId(), organization.getName(), organization.getSlug()));
    }

    private String uniqueSlug(String organizationName) {
        String base = SlugGenerator.slug(organizationName);
        String candidate = base;
        while (organizationRepository.findBySlug(candidate).isPresent()) {
            candidate = base + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return candidate;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
