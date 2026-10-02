package com.pesaguard.backend.credentials.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.environment.domain.EnvironmentLimits;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.project.domain.ProjectStatus;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@Service
public class ApiKeyService {

    private static final Duration MAX_LIFETIME = Duration.ofDays(366);

    private final ApiKeyRepository apiKeyRepository;
    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final ApiKeyGenerator generator;
    private final AuditService auditService;
    private final com.pesaguard.backend.rbac.application.AuthorizationService authorizationService;
    private final com.pesaguard.backend.environment.infrastructure.EnvironmentLimitsRepository environmentLimitsRepository;
    private final ApiKeyHistoryRepository historyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, ProjectRepository projectRepository,
            ProjectEnvironmentRepository environmentRepository, ApiKeyGenerator generator,
            AuditService auditService,
            com.pesaguard.backend.rbac.application.AuthorizationService authorizationService,
            com.pesaguard.backend.environment.infrastructure.EnvironmentLimitsRepository environmentLimitsRepository,
            ApiKeyHistoryRepository historyRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.generator = generator;
        this.auditService = auditService;
        this.authorizationService = authorizationService;
        this.environmentLimitsRepository = environmentLimitsRepository;
        this.historyRepository = historyRepository;
    }

    @Transactional
    public CreatedApiKeyView issue(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            CreateApiKeyRequest request, Instant now) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_CREATE);
        var project = projectRepository.findByIdAndOrganizationId(projectId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Project"));
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new ResourceNotFoundException("Project");
        }
        var environment = environmentRepository.findByIdAndOrganizationIdAndProjectId(
                        environmentId, principal.organizationId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
        if (environment.getStatus() != com.pesaguard.backend.environment.domain.EnvironmentStatus.ACTIVE) {
            throw new ResourceNotFoundException("Environment");
        }
        enforceApiKeyLimit(principal, projectId, environmentId);
        Duration lifetime = parseLifetime(request.expiresIn());
        ApiKeyGenerator.GeneratedKey generated = generator.generate();
        ApiKey key = apiKeyRepository.saveAndFlush(ApiKey.create(
                principal.organizationId(), projectId, environmentId, request.name().trim(),
                generated.prefix(), generator.hash(generated.rawKey()), ScopeCodec.encode(request.scopes()),
                now.plus(lifetime), principal.userId()));
        recordHistory(principal, key, ApiKeyStatus.CREATED, ApiKeyStatus.CREATED, "api_key.created", null);
        key.activate();
        apiKeyRepository.saveAndFlush(key);
        recordHistory(principal, key, ApiKeyStatus.CREATED, ApiKeyStatus.ACTIVE, "api_key.activated", null);
        auditService.append(principal.organizationId(), principal.userId(), "api_key.issued", "api_key",
                key.getId().toString(), RequestContext.currentRequestId(),
                Map.of("projectId", projectId.toString(), "environmentId", environmentId.toString()));
        return new CreatedApiKeyView(key.getId(), key.getName(), generated.rawKey(), key.getKeyPrefix(),
                request.scopes(), key.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public List<ApiKeyView> list(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_READ);
        requireEnvironment(principal, projectId, environmentId);
        return apiKeyRepository.findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
                        principal.organizationId(), projectId, environmentId)
                .stream().map(key -> toView(key, java.time.Instant.now())).toList();
    }

    @Transactional
    public void revoke(AuthenticatedUser principal, UUID projectId, UUID environmentId, UUID keyId, Instant now) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_REVOKE);
        requireEnvironment(principal, projectId, environmentId);
        ApiKey key = requireKey(principal, projectId, environmentId, keyId);
        ApiKeyStatus from = key.getStatus();
        boolean changed = from != ApiKeyStatus.REVOKED;
        key.revoke(now);
        apiKeyRepository.saveAndFlush(key);
        if (changed) {
            recordHistory(principal, key, from, ApiKeyStatus.REVOKED, "api_key.revoked", null);
            auditService.append(principal.organizationId(), principal.userId(), "api_key.revoked", "api_key",
                    keyId.toString(), RequestContext.currentRequestId(), Map.of());
        }
    }

    @Transactional
    public ApiKeyView suspend(AuthenticatedUser principal, UUID projectId, UUID environmentId, UUID keyId,
            Instant now) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_REVOKE);
        requireEnvironment(principal, projectId, environmentId);
        ApiKey key = requireKey(principal, projectId, environmentId, keyId);
        ApiKeyStatus from = key.getStatus();
        key.suspend(now);
        apiKeyRepository.saveAndFlush(key);
        recordHistory(principal, key, from, ApiKeyStatus.SUSPENDED, "api_key.suspended", null);
        return toView(key, now);
    }

    @Transactional
    public ApiKeyView resume(AuthenticatedUser principal, UUID projectId, UUID environmentId, UUID keyId) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_REVOKE);
        requireEnvironment(principal, projectId, environmentId);
        ApiKey key = requireKey(principal, projectId, environmentId, keyId);
        ApiKeyStatus from = key.getStatus();
        key.resume();
        apiKeyRepository.saveAndFlush(key);
        recordHistory(principal, key, from, ApiKeyStatus.ACTIVE, "api_key.resumed", null);
        return toView(key, Instant.now());
    }

    /**
     * Rotation issues a brand new secret and revokes the previous key in the same
     * transaction. There is no overlap window: the old secret stops working the
     * moment the new one is returned, which is the safe default for a credential.
     */
    @Transactional
    public CreatedApiKeyView rotate(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            UUID keyId, Instant now) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_ROTATE);
        requireEnvironment(principal, projectId, environmentId);
        ApiKey current = requireKey(principal, projectId, environmentId, keyId);
        if (current.getStatus().isTerminal() || current.getExpiresAt() == null
                || !current.getExpiresAt().isAfter(now)) {
            throw new com.pesaguard.backend.common.exception.BusinessException(
                    org.springframework.http.HttpStatus.CONFLICT, "API_KEY_NOT_ROTATABLE",
                    "A revoked or expired key cannot be rotated.");
        }
        enforceApiKeyLimit(principal, projectId, environmentId);

        ApiKeyStatus previousStatus = current.getStatus();
        current.revoke(now);
        ApiKeyGenerator.GeneratedKey generated = generator.generate();
        ApiKey rotated = apiKeyRepository.saveAndFlush(ApiKey.create(
                principal.organizationId(), projectId, environmentId, current.getName(),
                generated.prefix(), generator.hash(generated.rawKey()),
                ScopeCodec.encode(current.scopeSet()), current.getExpiresAt(), principal.userId()));
        rotated.restrictToIps(ScopeCodec.decode(current.getIpAllowlist()));
        rotated.markRotatedFrom(current.getId());
        rotated.activate();
        apiKeyRepository.saveAndFlush(rotated);

        recordHistory(principal, current, previousStatus, ApiKeyStatus.REVOKED, "api_key.rotated_out",
                "replaced by " + rotated.getId());
        recordHistory(principal, rotated, null, ApiKeyStatus.ACTIVE, "api_key.rotated_in",
                "replaces " + current.getId());
        auditService.append(principal.organizationId(), principal.userId(), "api_key.rotated", "api_key",
                current.getId().toString(), RequestContext.currentRequestId(),
                Map.of("rotatedKeyId", rotated.getId().toString()));
        return new CreatedApiKeyView(rotated.getId(), rotated.getName(), generated.rawKey(),
                generated.prefix(), rotated.scopeSet(), rotated.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public List<ApiKeyHistoryView> history(AuthenticatedUser principal, UUID projectId,
            UUID environmentId, UUID keyId) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_READ);
        requireEnvironment(principal, projectId, environmentId);
        requireKey(principal, projectId, environmentId, keyId);
        return historyRepository.findByApiKeyIdOrderByCreatedAtDesc(keyId).stream()
                .map(entry -> new ApiKeyHistoryView(entry.getId(), entry.getApiKeyId(), entry.getAction(),
                        entry.getFromStatus() == null ? null : entry.getFromStatus().name(),
                        entry.getToStatus().name(), entry.getActorUserId(), entry.getReason(),
                        entry.getCreatedAt()))
                .toList();
    }

    private ApiKey requireKey(AuthenticatedUser principal, UUID projectId, UUID environmentId, UUID keyId) {
        return apiKeyRepository
                .findByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
                        keyId, principal.organizationId(), projectId, environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("API key"));
    }

    private void recordHistory(AuthenticatedUser principal, ApiKey key, ApiKeyStatus from,
            ApiKeyStatus to, String action, String reason) {
        historyRepository.save(ApiKeyHistory.record(principal.organizationId(), key.getId(), from, to,
                action, principal.userId(), reason, Instant.now()));
    }

    private void enforceApiKeyLimit(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        EnvironmentLimits limits = environmentLimitsRepository.findByEnvironmentId(environmentId)
                .orElse(null);
        if (limits == null) {
            return;
        }
        long active = apiKeyRepository.countByEnvironmentIdAndStatus(environmentId, ApiKeyStatus.ACTIVE);
        if (active >= limits.getMaxApiKeys()) {
            throw new com.pesaguard.backend.common.exception.BusinessException(
                    HttpStatus.CONFLICT, "ENVIRONMENT_API_KEY_LIMIT_REACHED",
                    "This environment already has the maximum number of active API keys.");
        }
    }

    private Duration parseLifetime(String value) {
        try {
            Duration lifetime = Duration.parse(value);
            if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(MAX_LIFETIME) > 0) {
                throw new IllegalArgumentException("lifetime out of range");
            }
            return lifetime;
        } catch (RuntimeException exception) {
            throw new com.pesaguard.backend.common.exception.BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_EXPIRATION",
                    "expiresIn must be a positive ISO-8601 duration no longer than 366 days.");
        }
    }

    private void requireEnvironment(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        projectRepository.findByIdAndOrganizationId(projectId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Project"));
        environmentRepository.findByIdAndOrganizationIdAndProjectId(
                        environmentId, principal.organizationId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
    }

    private ApiKeyView toView(ApiKey key, Instant now) {
        return new ApiKeyView(key.getId(), key.getProjectId(), key.getEnvironmentId(), key.getName(),
                key.getKeyPrefix(), key.scopeSet(), key.effectiveStatus(now).name(),
                key.getExpiresAt(), key.getLastUsedAt(), key.getRevokedAt(), key.getRequestCount(),
                key.getRotatedFromId(), ScopeCodec.decode(key.getIpAllowlist()), key.getCreatedAt());
    }
}
