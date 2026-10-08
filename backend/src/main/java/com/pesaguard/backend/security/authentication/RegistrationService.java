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
import com.pesaguard.backend.onboarding.domain.DeveloperOnboardingProgress;
import com.pesaguard.backend.onboarding.infrastructure.DeveloperOnboardingProgressRepository;
import com.pesaguard.backend.member.application.EmailVerificationService;

@Service
public class RegistrationService {

    private final UserAccountRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationSecuritySettingsRepository settingsRepository;
    private final OrganizationMembershipHistoryRepository historyRepository;
    private final DeveloperOnboardingProgressRepository onboardingProgressRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final UsernamePolicy usernamePolicy;
    private final AuditService auditService;
    private final EmailVerificationService emailVerificationService;
    private final ApplicationProperties properties;
    private final Clock clock;

    public RegistrationService(
            UserAccountRepository userRepository,
            OrganizationRepository organizationRepository,
            OrganizationMembershipRepository membershipRepository,
            OrganizationSecuritySettingsRepository settingsRepository,
            OrganizationMembershipHistoryRepository historyRepository,
            DeveloperOnboardingProgressRepository onboardingProgressRepository,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            UsernamePolicy usernamePolicy,
            AuditService auditService,
            EmailVerificationService emailVerificationService,
            ApplicationProperties properties,
            Clock clock) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.settingsRepository = settingsRepository;
        this.historyRepository = historyRepository;
        this.onboardingProgressRepository = onboardingProgressRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.usernamePolicy = usernamePolicy;
        this.auditService = auditService;
        this.emailVerificationService = emailVerificationService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public RegistrationResponse register(RegisterRequest request) {
        if (!properties.security().registrationEnabled()) {
            throw new ResourceConflictException("REGISTRATION_DISABLED", "Public registration is disabled.");
        }
        String email = normalizeEmail(request.email());
        passwordPolicy.validate(request.password(), email, request.displayName());
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ResourceConflictException("EMAIL_ALREADY_REGISTERED", "An account already exists for this email.");
        }
        String username = usernamePolicy.validate(request.username());
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new ResourceConflictException("USERNAME_ALREADY_TAKEN",
                    "That username is already in use. Choose another one.");
        }

        UserAccount user = userRepository.saveAndFlush(
                UserAccount.create(email, username, request.displayName().trim(),
                        passwordEncoder.encode(request.password()), request.phoneNumber()));
        String requestedOrganizationName = resolveOrganizationName(request);
        String organizationSlug = uniqueSlug(requestedOrganizationName);
        Instant now = clock.instant();
        Organization organization = Organization.create(
                requestedOrganizationName, organizationSlug, user.getId(), now);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        String description = request.organizationDescription() == null ? "" : request.organizationDescription().trim();
        if (!description.isBlank()) {
            metadata.put("description", description);
        }
        organization.update(requestedOrganizationName,
                com.pesaguard.backend.organization.domain.OrganizationType.DEVELOPER,
                metadata.isEmpty() ? Map.of() : metadata);
        organizationRepository.saveAndFlush(organization);
        OrganizationMembership membership = membershipRepository.saveAndFlush(
                OrganizationMembership.owner(organization, user));
        settingsRepository.saveAndFlush(
                OrganizationSecuritySettings.defaults(organization.getId(), now));
        historyRepository.save(
                OrganizationMembershipHistory.record(membership, null, null, user.getId(),
                        "organization.created", now));
        onboardingProgressRepository.save(DeveloperOnboardingProgress.start(user.getId(), now));

        EmailVerificationService.VerificationChallenge challenge = emailVerificationService.issue(user);
        auditService.append(
                organization.getId(),
                user.getId(),
                "organization.created",
                "organization",
                organization.getId().toString(),
                RequestContext.currentRequestId(),
                Map.of("membershipId", membership.getId().toString(),
                        "termsAccepted", request.termsAccepted(),
                        "nameProvidedByDeveloper", true));

        return new RegistrationResponse(
                user.getEmail(), organization.getName(), true,
                challenge.expiresAt(), challenge.resendAvailableAt(), challenge.issuedAt(), user.getUsername());
    }

    private String resolveOrganizationName(RegisterRequest request) {
        String provided = request.organizationName() == null ? "" : request.organizationName().trim();
        if (!provided.isBlank()) {
            return provided;
        }
        String fallback = request.displayName() == null ? "" : request.displayName().trim();
        if (!fallback.isBlank()) {
            return fallback + " Workspace";
        }
        return "Developer Workspace";
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
