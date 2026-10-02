package com.pesaguard.backend.credentials.api;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.authentication.IpRangeMatcher;

/**
 * Resolves a presented API key to the key it belongs to.
 *
 * <p>This is the enforcement point for key state: a revoked, expired, suspended,
 * or not-yet-activated key never authenticates, and a key with an IP allowlist
 * only authenticates from those networks. Lookup is by HMAC hash, so the database
 * cannot be queried with a prefix to enumerate keys.
 */
@Component
public class ApiKeyAuthenticator {

    private final ApiKeyRepository apiKeyRepository;
    private final CredentialCryptoService crypto;
    private final IpRangeMatcher ipRangeMatcher;
    private final Clock clock;

    public ApiKeyAuthenticator(ApiKeyRepository apiKeyRepository, CredentialCryptoService crypto,
            IpRangeMatcher ipRangeMatcher, Clock clock) {
        this.apiKeyRepository = apiKeyRepository;
        this.crypto = crypto;
        this.ipRangeMatcher = ipRangeMatcher;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<AuthenticatedApiKey> authenticate(String presentedKey, String remoteAddress) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Optional.empty();
        }
        return apiKeyRepository.findBySecretHash(crypto.hmacSha256(presentedKey.trim()))
                .filter(key -> key.isUsable(clock.instant()))
                .filter(key -> ipAllowed(key, remoteAddress))
                .map(key -> new AuthenticatedApiKey(key.getId(), key.getOrganizationId(),
                        key.getProjectId(), key.getEnvironmentId(), key.scopeSet()));
    }

    /**
     * Records successful use. Kept separate from {@link #authenticate} so a read-only
     * check never mutates usage metadata.
     */
    @Transactional
    public void recordUsage(ApiKey key, String remoteAddress) {
        key.recordUsage(clock.instant(), remoteAddress);
        apiKeyRepository.saveAndFlush(key);
    }

    private boolean ipAllowed(ApiKey key, String remoteAddress) {
        if (key.getIpAllowlist() == null || key.getIpAllowlist().isBlank()) {
            return true;
        }
        return ipRangeMatcher.isAllowed(remoteAddress, Set.of(key.getIpAllowlist().split(",")));
    }

    /**
     * The authenticated key's identity and grants. Deliberately carries no secret
     * material of any kind.
     */
    public record AuthenticatedApiKey(
            UUID keyId,
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            Set<String> scopes) {
    }
}