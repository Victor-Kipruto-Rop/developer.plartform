package com.pesaguard.backend.member.application;

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
import com.pesaguard.backend.credentials.api.ApiKey;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.credentials.api.ApiKeyService;
import com.pesaguard.backend.credentials.api.ScopeCodec;
import com.pesaguard.backend.member.api.AccountDataExport;
import com.pesaguard.backend.member.api.AccountLifecycleView;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.domain.UserStatus;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.organization.domain.OrganizationMembershipHistory;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipHistoryRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.security.authentication.MfaService;
import com.pesaguard.backend.security.sessions.RefreshTokenService;
import com.pesaguard.backend.security.sessions.SessionService;

@Service
public class UserLifecycleService {

    public static final Duration DELETION_GRACE_PERIOD = Duration.ofDays(30);

    private final UserAccountRepository accountRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationMembershipHistoryRepository membershipHistoryRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final PasswordRecoveryService passwordRecoveryService;
    private final MfaService mfaService;
    private final SessionService sessionService;
    private final RefreshTokenService refreshTokenService;
    private final ApiKeyService apiKeyService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserLifecycleService(
            UserAccountRepository accountRepository,
            OrganizationMembershipRepository membershipRepository,
            OrganizationMembershipHistoryRepository membershipHistoryRepository,
            ApiKeyRepository apiKeyRepository,
            PasswordRecoveryService passwordRecoveryService,
            MfaService mfaService,
            SessionService sessionService,
            RefreshTokenService refreshTokenService,
            ApiKeyService apiKeyService,
            AuditService auditService,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.membershipRepository = membershipRepository;
        this.membershipHistoryRepository = membershipHistoryRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.passwordRecoveryService = passwordRecoveryService;
        this.mfaService = mfaService;
        this.sessionService = sessionService;
        this.refreshTokenService = refreshTokenService;
        this.apiKeyService = apiKeyService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserAccount requireAccount(UUID userId) {
        return accountRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "ACCOUNT_NOT_FOUND", "The account could not be found."));
    }

    @Transactional(readOnly = true)
    public AccountLifecycleView lifecycle(UUID userId) {
        UserAccount account = requireAccount(userId);
        return new AccountLifecycleView(
                account.getStatus(),
                account.getDeletionRequestedAt(),
                account.deletionCompletesAt(DELETION_GRACE_PERIOD).orElse(null),
                hasOwnedOrganizations(userId));
    }

    @Transactional
    public AccountDataExport exportAccount(
            UUID userId, UUID organizationId, String currentPassword, String mfaCode) {
        UserAccount account = requireAccount(userId);
        requireStepUp(userId, currentPassword, mfaCode);
        List<AccountDataExport.Membership> memberships = membershipRepository.findAllByUserId(userId).stream()
                .map(membership -> new AccountDataExport.Membership(
                        membership.getOrganization().getId(),
                        membership.getOrganization().getName(),
                        membership.getRole(),
                        membership.getStatus(),
                        membership.getCreatedAt()))
                .toList();
        List<AccountDataExport.CreatedApiKey> createdApiKeys = apiKeyRepository
                .findByCreatedByOrderByCreatedAtDesc(userId).stream()
                .map(this::toExportKey)
                .toList();
        appendAudit(organizationId, userId, "account.data_exported",
                Map.of("membershipCount", memberships.size(), "apiKeyCount", createdApiKeys.size()));
        return new AccountDataExport(
                clock.instant(),
                new AccountDataExport.Profile(
                        account.getId(), account.getEmail(), account.getUsername(), account.getDisplayName(),
                        account.getStatus(), account.getCreatedAt(), account.getEmailVerifiedAt()),
                memberships,
                createdApiKeys);
    }

    @Transactional
    public UserAccount requestDeletion(
            UUID userId, UUID organizationId, String currentPassword, String mfaCode) {
        Instant now = clock.instant();
        UserAccount account = lockAccount(userId);
        requireStepUp(userId, currentPassword, mfaCode);
        requireNotDeleted(account);
        if (account.getStatus() == UserStatus.PENDING_DELETION) {
            throw conflict("DELETION_ALREADY_PENDING", "A deletion request is already pending.");
        }
        requireNoOwnedOrganizations(userId);
        account.requestDeletion(now);
        accountRepository.save(account);
        appendAudit(organizationId, userId, "account.deletion.requested",
                Map.of("gracePeriodDays", DELETION_GRACE_PERIOD.toDays()));
        return account;
    }

    @Transactional
    public UserAccount cancelDeletion(
            UUID userId, UUID organizationId, String currentPassword, String mfaCode) {
        Instant now = clock.instant();
        UserAccount account = lockAccount(userId);
        requireStepUp(userId, currentPassword, mfaCode);
        if (account.getStatus() != UserStatus.PENDING_DELETION) {
            throw conflict("DELETION_NOT_PENDING", "This account has no pending deletion to cancel.");
        }
        if (account.deletionCompletesAt(DELETION_GRACE_PERIOD)
                .filter(completeAt -> !now.isBefore(completeAt)).isPresent()) {
            throw conflict("DELETION_GRACE_EXPIRED", "The deletion grace period has elapsed.");
        }
        account.cancelDeletion(now);
        accountRepository.save(account);
        appendAudit(organizationId, userId, "account.deletion.cancelled", Map.of());
        return account;
    }

    @Transactional
    public void deactivate(
            UUID userId, UUID organizationId, String currentPassword, String mfaCode) {
        Instant now = clock.instant();
        UserAccount account = lockAccount(userId);
        requireStepUp(userId, currentPassword, mfaCode);
        if (account.getStatus() != UserStatus.ACTIVE) {
            throw conflict("ACCOUNT_NOT_ACTIVE", "Only an active account can be deactivated.");
        }
        account.deactivate(now);
        accountRepository.save(account);
        revokePersonalCredentials(userId, now, "ACCOUNT_DEACTIVATED");
        appendAudit(organizationId, userId, "account.deactivated", Map.of());
    }

    /**
     * Completes a due deletion. Returns false while grace remains; ownership
     * conflicts deliberately throw and leave all account data untouched.
     */
    @Transactional
    public boolean completeDeletion(UUID userId) {
        Instant now = clock.instant();
        UserAccount account = lockAccount(userId);
        if (account.getStatus() != UserStatus.PENDING_DELETION) {
            return false;
        }
        if (account.deletionCompletesAt(DELETION_GRACE_PERIOD)
                .filter(completeAt -> !now.isBefore(completeAt)).isEmpty()) {
            return false;
        }
        List<OrganizationMembership> memberships = lockMembershipsAndRequireTransfer(userId);
        revokePersonalCredentials(userId, now, "ACCOUNT_DELETED");
        for (OrganizationMembership membership : memberships) {
            if (membership.getStatus() != MembershipStatus.REVOKED
                    && membership.getRole() != OrganizationRole.OWNER) {
                MembershipStatus oldStatus = membership.getStatus();
                OrganizationRole oldRole = membership.getRole();
                membership.remove();
                membershipRepository.save(membership);
                membershipHistoryRepository.save(OrganizationMembershipHistory.record(
                        membership, oldStatus, oldRole, userId, "account_deleted", now));
            }
            appendAudit(membership.getOrganization().getId(), userId, "account.deletion.completed",
                    Map.of("accountId", userId.toString()));
        }

        account.completeDeletion(now);
        account.anonymizeForDeletion(passwordEncoder.encode(
                UUID.randomUUID().toString() + UUID.randomUUID()), now);
        accountRepository.save(account);
        return true;
    }

    @Transactional(readOnly = true)
    public boolean hasOwnedOrganizations(UUID userId) {
        return membershipRepository.existsByUserIdAndRole(userId, OrganizationRole.OWNER);
    }

    private UserAccount lockAccount(UUID userId) {
        return accountRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "ACCOUNT_NOT_FOUND", "The account could not be found."));
    }

    private void requireStepUp(UUID userId, String currentPassword, String mfaCode) {
        passwordRecoveryService.requireCurrentPassword(userId, currentPassword);
        if (mfaService.isEnabled(userId)
                && (mfaCode == null || mfaCode.isBlank() || !mfaService.verify(userId, mfaCode))) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "MFA_INVALID",
                    "The verification code is incorrect or has expired.");
        }
    }

    private void revokePersonalCredentials(UUID userId, Instant now, String reason) {
        sessionService.revokeActiveByUserId(userId);
        refreshTokenService.revokeAllForUser(userId, reason);
        mfaService.eraseCredentials(userId);
        passwordRecoveryService.revokeOutstanding(userId);
        apiKeyService.revokeCreatedByUserId(userId, now);
    }

    private void requireNoOwnedOrganizations(UUID userId) {
        lockMembershipsAndRequireTransfer(userId);
    }

    private List<OrganizationMembership> lockMembershipsAndRequireTransfer(UUID userId) {
        List<OrganizationMembership> memberships = membershipRepository.findAllByUserIdForUpdate(userId);
        if (memberships.stream().anyMatch(membership -> membership.getRole() == OrganizationRole.OWNER)) {
            throw conflict("ORGANIZATION_OWNERSHIP_TRANSFER_REQUIRED",
                    "Transfer ownership of every organization before deleting this account.");
        }
        return memberships;
    }

    private void requireNotDeleted(UserAccount account) {
        if (account.getStatus() == UserStatus.DELETED) {
            throw conflict("ACCOUNT_DELETED", "This account has already been deleted.");
        }
    }

    private void appendAudit(UUID organizationId, UUID userId, String action, Map<String, ?> metadata) {
        auditService.append(organizationId, userId, action, "user_account", userId.toString(),
                RequestContext.currentRequestId(), metadata);
    }

    private AccountDataExport.CreatedApiKey toExportKey(ApiKey key) {
        return new AccountDataExport.CreatedApiKey(
                key.getId(), key.getOrganizationId(), key.getProjectId(), key.getEnvironmentId(),
                key.getName(), key.getKeyPrefix(),
                ScopeCodec.decode(key.getScopes()).stream().sorted().toList(), key.getStatus(),
                key.getCreatedAt(), key.getExpiresAt(), key.getLastUsedAt());
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
