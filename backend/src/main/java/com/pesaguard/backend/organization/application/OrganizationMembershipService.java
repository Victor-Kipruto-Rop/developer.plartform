package com.pesaguard.backend.organization.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceConflictException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.config.ApplicationProperties;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.organization.api.AcceptInvitationRequest;
import com.pesaguard.backend.organization.api.ChangeMemberRoleRequest;
import com.pesaguard.backend.organization.api.CreateInvitationRequest;
import com.pesaguard.backend.organization.api.CreatedInvitationView;
import com.pesaguard.backend.organization.api.InvitationView;
import com.pesaguard.backend.organization.api.MemberView;
import com.pesaguard.backend.organization.api.MembershipHistoryView;
import com.pesaguard.backend.organization.api.UpdateMemberStatusRequest;
import com.pesaguard.backend.organization.domain.InvitationStatus;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationInvitation;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.domain.OrganizationMembershipHistory;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.organization.domain.OrganizationSecuritySettings;
import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.organization.infrastructure.OrganizationInvitationRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipHistoryRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationSecuritySettingsRepository;
import com.pesaguard.backend.security.authentication.PasswordPolicy;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.authentication.RegistrationService;
import com.pesaguard.backend.security.authentication.SessionOrganizationResponse;
import com.pesaguard.backend.security.authentication.SessionUserResponse;
import com.pesaguard.backend.security.authentication.AuthenticationResponse;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.security.sessions.SessionService;
import com.pesaguard.backend.security.throttling.RequestThrottleService;
import com.pesaguard.backend.tenancy.TenantEventPublisher;

@Service
public class OrganizationMembershipService {

    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationMembershipHistoryRepository historyRepository;
    private final OrganizationInvitationRepository invitationRepository;
    private final OrganizationSecuritySettingsRepository settingsRepository;
    private final OrganizationRepository organizationRepository;
    private final UserAccountRepository userRepository;
    private final OrganizationAuthorization authorization;
    private final CredentialCryptoService crypto;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final TenantEventPublisher eventPublisher;
    private final ApplicationProperties properties;
    private final RequestThrottleService throttleService;
    private final Clock clock;

    public OrganizationMembershipService(
            OrganizationMembershipRepository membershipRepository,
            OrganizationMembershipHistoryRepository historyRepository,
            OrganizationInvitationRepository invitationRepository,
            OrganizationSecuritySettingsRepository settingsRepository,
            OrganizationRepository organizationRepository,
            UserAccountRepository userRepository,
            OrganizationAuthorization authorization,
            CredentialCryptoService crypto,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            SessionService sessionService,
            AuditService auditService,
            TenantEventPublisher eventPublisher,
            ApplicationProperties properties,
            RequestThrottleService throttleService,
            Clock clock) {
        this.membershipRepository = membershipRepository;
        this.historyRepository = historyRepository;
        this.invitationRepository = invitationRepository;
        this.settingsRepository = settingsRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.authorization = authorization;
        this.crypto = crypto;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.throttleService = throttleService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MemberView> members(AuthenticatedUser principal) {
        authorization.requireRole(principal, java.util.Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN,
                OrganizationRole.DEVELOPER, OrganizationRole.VIEWER));
        return membershipRepository.findAllByOrganizationId(principal.organizationId()).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<MembershipHistoryView> history(AuthenticatedUser principal, UUID membershipId) {
        authorization.requireRole(principal, java.util.Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN));
        membershipRepository.findByIdAndOrganizationId(membershipId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Membership"));
        return historyRepository.findByMembershipIdOrderByCreatedAtDesc(membershipId).stream().map(this::toHistoryView).toList();
    }

    @Transactional
    public MemberView updateStatus(AuthenticatedUser principal, UUID membershipId, UpdateMemberStatusRequest request) {
        authorization.requireRole(principal, java.util.Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN));
        OrganizationMembership membership = membershipRepository.findByIdAndOrganizationId(
                membershipId, principal.organizationId()).orElseThrow(() -> new ResourceNotFoundException("Membership"));
        if (membership.getRole() == OrganizationRole.OWNER) {
            throw conflict("OWNER_MEMBERSHIP_PROTECTED", "The organization owner membership cannot be changed.");
        }
        MembershipStatus previous = membership.getStatus();
        if (request.status() == MembershipStatus.SUSPENDED) {
            membership.suspend();
        } else if (request.status() == MembershipStatus.ACTIVE) {
            membership.activate();
        } else if (request.status() == MembershipStatus.REVOKED) {
            membership.remove();
        } else {
            throw conflict("UNSUPPORTED_MEMBERSHIP_STATUS", "Unsupported membership status.");
        }
        membershipRepository.saveAndFlush(membership);
        historyRepository.save(OrganizationMembershipHistory.record(membership, previous, membership.getRole(),
                principal.userId(), request.reason(), clock.instant()));
        if (request.status() != MembershipStatus.ACTIVE) {
            sessionService.revokeActiveByMembershipId(membershipId);
        }
        audit(principal.organizationId(), principal.userId(), "organization.membership.status_changed",
                Map.of("membershipId", membershipId.toString(), "status", request.status().name()));
        return toView(membership);
    }

    @Transactional
    public MemberView updateRole(AuthenticatedUser principal, UUID membershipId, ChangeMemberRoleRequest request) {
        authorization.requireOwner(principal);
        OrganizationMembership membership = membershipRepository.findByIdAndOrganizationId(
                membershipId, principal.organizationId()).orElseThrow(() -> new ResourceNotFoundException("Membership"));
        if (membership.getRole() == OrganizationRole.OWNER) {
            throw conflict("OWNER_MEMBERSHIP_PROTECTED", "The organization owner membership cannot be changed.");
        }
        if (request.role() == OrganizationRole.OWNER) {
            throw conflict("OWNERSHIP_TRANSFER_REQUIRED", "Use the ownership transfer operation to assign ownership.");
        }
        OrganizationRole previous = membership.getRole();
        membership.changeRole(request.role());
        membershipRepository.saveAndFlush(membership);
        historyRepository.save(OrganizationMembershipHistory.record(membership, membership.getStatus(), previous,
                principal.userId(), "role.changed", clock.instant()));
        audit(principal.organizationId(), principal.userId(), "organization.membership.role_changed",
                Map.of("membershipId", membershipId.toString(), "role", request.role().name()));
        return toView(membership);
    }

    @Transactional
    public CreatedInvitationView invite(AuthenticatedUser principal, CreateInvitationRequest request) {
        authorization.requireRole(principal, java.util.Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN));
        if (request.role() == OrganizationRole.OWNER) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "OWNERSHIP_TRANSFER_REQUIRED",
                    "Ownership is transferred explicitly, not by invitation.");
        }
        Organization organization = organizationRepository.findByIdForUpdate(principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization"));
        if (organization.getStatus() != OrganizationStatus.ACTIVE) {
            throw conflict("ORGANIZATION_NOT_ACTIVE", "Invitations require an active organization.");
        }
        String email = RegistrationService.normalizeEmail(request.email());
        if (membershipRepository.existsByOrganizationIdAndUserEmailIgnoreCase(organization.getId(), email)) {
            throw new ResourceConflictException("MEMBER_ALREADY_EXISTS", "The user is already a member of this organization.");
        }
        if (invitationRepository.existsByOrganizationIdAndEmailIgnoreCaseAndStatus(
                organization.getId(), email, InvitationStatus.PENDING)) {
            throw new ResourceConflictException("INVITATION_ALREADY_PENDING",
                    "A pending invitation already exists for this email address.");
        }
        String rawToken = crypto.randomToken(32);
        Instant now = clock.instant();
        OrganizationInvitation invitation = invitationRepository.saveAndFlush(OrganizationInvitation.create(
                organization.getId(), email, request.role(), crypto.sha256(rawToken),
                now.plus(properties.security().invitationTtl()), principal.userId()));
        audit(organization.getId(), principal.userId(), "organization.invitation.created",
                Map.of("invitationId", invitation.getId().toString(), "role", request.role().name()));
        return new CreatedInvitationView(invitation.getId(), email, request.role().name(), rawToken,
                invitation.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public List<InvitationView> invitations(AuthenticatedUser principal) {
        authorization.requireRole(principal, java.util.Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN));
        return invitationRepository.findByOrganizationIdOrderByCreatedAtDesc(principal.organizationId())
                .stream().map(this::toInvitationView).toList();
    }

    @Transactional
    public void revokeInvitation(AuthenticatedUser principal, UUID invitationId) {
        authorization.requireRole(principal, java.util.Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN));
        OrganizationInvitation invitation = invitationRepository.findByIdAndOrganizationId(
                invitationId, principal.organizationId()).orElseThrow(() -> new ResourceNotFoundException("Invitation"));
        Instant now = clock.instant();
        invitation.expire(now);
        boolean changed = invitation.getStatus() == InvitationStatus.PENDING;
        invitation.revoke(now);
        invitationRepository.saveAndFlush(invitation);
        if (changed) {
            audit(principal.organizationId(), principal.userId(), "organization.invitation.revoked",
                    Map.of("invitationId", invitationId.toString()));
        }
    }



    @Transactional
    public AuthenticationResponse accept(AcceptInvitationRequest request, String remoteAddress) {
        throttleInvitationAcceptance(request.token(), remoteAddress);
        String email = RegistrationService.normalizeEmail(request.email());
        Instant now = clock.instant();
        OrganizationInvitation invitation = invitationRepository.findByTokenHash(crypto.sha256(request.token()))
                .orElseThrow(() -> new com.pesaguard.backend.common.exception.UnauthorizedException(
                        "INVALID_INVITATION", "The invitation is invalid or expired."));
        invitation.expire(now);
        if (invitation.getStatus() != InvitationStatus.PENDING || !email.equals(invitation.getEmail())) {
            invitationRepository.save(invitation);
            throw new com.pesaguard.backend.common.exception.UnauthorizedException(
                    "INVALID_INVITATION", "The invitation is invalid or expired.");
        }
        Organization organization = organizationRepository.findByIdForUpdate(invitation.getOrganizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization"));
        if (organization.getStatus() != OrganizationStatus.ACTIVE) {
            throw conflict("ORGANIZATION_NOT_ACTIVE", "The organization is not accepting memberships.");
        }
        if (invitation.getRole() == OrganizationRole.OWNER) {
            throw conflict("OWNERSHIP_TRANSFER_REQUIRED", "The invitation cannot assign ownership.");
        }
        if (membershipRepository.existsByOrganizationIdAndUserEmailIgnoreCase(organization.getId(), email)) {
            throw new ResourceConflictException("MEMBER_ALREADY_EXISTS", "The user is already a member of this organization.");
        }
        OrganizationSecuritySettings settings = settingsRepository.findById(organization.getId())
                .orElseGet(() -> settingsRepository.saveAndFlush(
                        OrganizationSecuritySettings.defaults(organization.getId(), now)));
        UserAccount user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            if (request.displayName() == null || request.displayName().isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "DISPLAY_NAME_REQUIRED",
                        "A display name is required when accepting an invitation.");
            }
            passwordPolicy.validate(request.password(), email, settings.getCredentialMinLength(), settings.getCredentialMaxLength());
            user = userRepository.saveAndFlush(UserAccount.create(email, request.displayName().trim(),
                    passwordEncoder.encode(request.password())));
        } else if (!user.isActive() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new com.pesaguard.backend.common.exception.UnauthorizedException(
                    "INVALID_INVITATION_CREDENTIALS", "The invitation credentials are invalid.");
        }
        OrganizationMembership membership = membershipRepository.saveAndFlush(
                OrganizationMembership.member(organization, user, invitation.getRole()));
        historyRepository.save(OrganizationMembershipHistory.record(membership, null, null, user.getId(),
                "invitation.accepted", now));
        invitation.accept(user.getId(), now);
        invitationRepository.saveAndFlush(invitation);
        SessionService.IssuedSession session = sessionService.issue(membership,
                Duration.ofMinutes(settings.getSessionTtlMinutes()));
        audit(organization.getId(), user.getId(), "organization.invitation.accepted",
                Map.of("invitationId", invitation.getId().toString(), "membershipId", membership.getId().toString()));
        return new AuthenticationResponse(session.token(), "Bearer", session.expiresAt(),
                new SessionUserResponse(user.getId(), user.getEmail(), user.getDisplayName()),
                new SessionOrganizationResponse(organization.getId(), organization.getName(), organization.getSlug()));
    }

    private void throttleInvitationAcceptance(String token, String remoteAddress) {
        int limit = properties.security().invitationAcceptAttemptLimit();
        java.time.Duration window = properties.security().invitationAcceptWindow();
        String addressSubject = subjectHash("invite_ip", remoteAddress == null ? "unknown" : remoteAddress);
        String tokenSubject = subjectHash("invite_token", token);
        throttleService.assertAllowed("invite_ip", addressSubject, limit);
        throttleService.assertAllowed("invite_token", tokenSubject, limit);
        throttleService.recordAttempt("invite_ip", addressSubject, limit, window);
        throttleService.recordAttempt("invite_token", tokenSubject, limit, window);
    }

    private String subjectHash(String type, String value) {
        return crypto.hmacSha256("invitation-throttle:" + type + ":" + value);
    }

    private MemberView toView(OrganizationMembership membership) {
        return new MemberView(membership.getId(), membership.getUser().getId(), membership.getUser().getEmail(),
                membership.getUser().getDisplayName(), membership.getRole(), membership.getStatus(),
                membership.getCreatedAt(), membership.getUpdatedAt());
    }

    private InvitationView toInvitationView(OrganizationInvitation invitation) {
        return new InvitationView(invitation.getId(), invitation.getEmail(), invitation.getRole(),
                invitation.getStatus(), invitation.getExpiresAt(), invitation.getInvitedBy(),
                invitation.getAcceptedAt(), invitation.getRevokedAt(), invitation.getCreatedAt());
    }

    private MembershipHistoryView toHistoryView(OrganizationMembershipHistory history) {
        return new MembershipHistoryView(history.getId(), history.getMembershipId(), history.getUserId(),
                history.getFromStatus(), history.getToStatus(), history.getFromRole(), history.getToRole(),
                history.getActorUserId(), history.getReason(), history.getCreatedAt());
    }

    private void audit(UUID organizationId, UUID actorId, String action, Map<String, ?> metadata) {
        auditService.append(organizationId, actorId, action, action.contains("invitation") ? "invitation" : "membership",
                organizationId.toString(), RequestContext.currentRequestId(), metadata);
        eventPublisher.publish(organizationId, actorId, action, organizationId, RequestContext.currentRequestId(), metadata);
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}

