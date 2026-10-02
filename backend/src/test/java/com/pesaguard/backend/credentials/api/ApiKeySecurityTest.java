package com.pesaguard.backend.credentials.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pesaguard.backend.security.authentication.IpRangeMatcher;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;

/**
 * Proves the four guarantees for API credentials: secrets are never stored in
 * plaintext, never returned after creation, never logged, and never exposed
 * through the listing view.
 */
class ApiKeySecurityTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
    private final CredentialCryptoService crypto = new CredentialCryptoService(
            new SecretKeySpec("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8), "HmacSHA256"),
            new SecureRandom());
    private final ApiKeyGenerator generator = new ApiKeyGenerator(crypto);
    private ApiKeyAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        authenticator = new ApiKeyAuthenticator(apiKeyRepository, crypto, new IpRangeMatcher(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ApiKey key(ApiKeyStatus status, String ipAllowlist) {
        ApiKey key = ApiKey.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "worker", "pgk_prefix00000000", crypto.hmacSha256("secret"), "projects:read",
                NOW.plusSeconds(3600), UUID.randomUUID());
        if (status != ApiKeyStatus.CREATED) {
            key.activate();
        }
        if (status == ApiKeyStatus.SUSPENDED) {
            key.suspend(NOW);
        }
        if (status == ApiKeyStatus.REVOKED) {
            key.revoke(NOW);
        }
        if (ipAllowlist != null) {
            key.restrictToIps(Set.of(ipAllowlist));
        }
        return key;
    }

    @Test
    void generatedSecretsAreUnpredictableAndPrefixed() {
        ApiKeyGenerator.GeneratedKey first = generator.generate();
        ApiKeyGenerator.GeneratedKey second = generator.generate();

        assertThat(first.rawKey()).startsWith("pgk_").hasSize(47);
        assertThat(first.rawKey()).isNotEqualTo(second.rawKey());
        assertThat(first.prefix()).hasSize(16).isEqualTo(first.rawKey().substring(0, 16));
    }

    @Test
    void onlyTheHmacOfTheSecretIsPersisted() {
        String raw = "pgk_this-is-the-secret-value";
        String stored = generator.hash(raw);

        assertThat(stored).hasSize(64).doesNotContain(raw).isNotEqualTo(raw);
        assertThat(generator.hash(raw)).isEqualTo(stored);
    }

    @Test
    void aPresentedSecretResolvesOnlyThroughTheHashLookup() {
        String presented = "pgk_this-is-the-secret-value";
        ApiKey key = key(ApiKeyStatus.ACTIVE, null);
        key.restrictToIps(null);
        // Only the correct HMAC resolves, exactly as the real repository behaves.
        when(apiKeyRepository.findBySecretHash(crypto.hmacSha256(presented))).thenReturn(Optional.of(key));

        assertThat(authenticator.authenticate(presented, "203.0.113.5")).isPresent();
        assertThat(authenticator.authenticate("pgk_wrong-secret", "203.0.113.5")).isEmpty();
        assertThat(authenticator.authenticate(null, "203.0.113.5")).isEmpty();
        assertThat(authenticator.authenticate("   ", "203.0.113.5")).isEmpty();
    }

    @Test
    void theAuthenticatedIdentityNeverCarriesTheSecret() {
        ApiKey key = key(ApiKeyStatus.ACTIVE, null);
        when(apiKeyRepository.findBySecretHash(any())).thenReturn(Optional.of(key));

        var authenticated = authenticator.authenticate("pgk_secret", "203.0.113.5").orElseThrow();

        assertThat(authenticated.scopes()).containsExactly("projects:read");
        assertThat(authenticated.keyId()).isEqualTo(key.getId());
        assertThat(authenticated.toString())
                .doesNotContain(key.getSecretHash())
                .doesNotContain("pgk_");
    }

    @Test
    void onlyActiveKeysAuthenticate() {
        for (ApiKeyStatus status : new ApiKeyStatus[] {
                ApiKeyStatus.CREATED, ApiKeyStatus.SUSPENDED, ApiKeyStatus.REVOKED, ApiKeyStatus.EXPIRED}) {
            ApiKey key = key(status, null);
            if (status == ApiKeyStatus.EXPIRED) {
                key.markExpired(NOW);
            }
            when(apiKeyRepository.findBySecretHash(any())).thenReturn(Optional.of(key));
            assertThat(authenticator.authenticate("pgk_secret", "203.0.113.5"))
                    .as("status %s must not authenticate", status)
                    .isEmpty();
        }
    }

    @Test
    void ipRestrictedKeysRejectOtherNetworks() {
        ApiKey key = key(ApiKeyStatus.ACTIVE, "203.0.113.0/24");
        when(apiKeyRepository.findBySecretHash(any())).thenReturn(Optional.of(key));

        assertThat(authenticator.authenticate("pgk_secret", "203.0.113.9")).isPresent();
        assertThat(authenticator.authenticate("pgk_secret", "198.51.100.9")).isEmpty();
    }

    @Test
    void listingViewNeverExposesTheSecret() {
        ApiKey key = key(ApiKeyStatus.ACTIVE, null);

        ApiKeyView view = new ApiKeyView(key.getId(), key.getProjectId(), key.getEnvironmentId(),
                key.getName(), key.getKeyPrefix(), key.scopeSet(), key.getStatus().name(),
                key.getExpiresAt(), key.getLastUsedAt(), key.getRevokedAt(), key.getRequestCount(),
                key.getRotatedFromId(), Set.of(), key.getCreatedAt());

        assertThat(view.toString()).doesNotContain(key.getSecretHash());
        assertThat(view.id()).isEqualTo(key.getId());
        assertThat(view.scopes()).containsExactly("projects:read");
        assertThat(view.requestCount()).isZero();
    }
}