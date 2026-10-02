package com.pesaguard.backend.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "pesaguard")
public record ApplicationProperties(@Valid @NotNull Security security,
        @Valid @NotNull Platform platform) {
    public ApplicationProperties {
        if (security != null) {
            security.validateOrigins();
        }
    }

    public record Security(
            @NotEmpty List<URI> allowedOrigins,
            @NotNull Duration sessionTtl,
            @NotBlank String credentialHmacKey,
            @NotBlank String auditHmacKey,
            boolean registrationEnabled,
            @Min(10) @Max(16) int bcryptStrength,
            @Min(1) @Max(100) int accountLoginFailureLimit,
            @Min(1) @Max(1000) int ipLoginFailureLimit,
            @NotNull Duration loginFailureWindow,
            @NotNull Duration invitationTtl,
            @Min(1) @Max(1000) int invitationAcceptAttemptLimit,
            @NotNull Duration invitationAcceptWindow) {

        private void validateOrigins() {
            if (allowedOrigins == null || allowedOrigins.isEmpty()) {
                throw new IllegalArgumentException("At least one allowed origin is required");
            }
            boolean hasUnsafeOrigin = allowedOrigins.stream()
                    .anyMatch(origin -> "*".equals(origin.getHost()) || origin.getHost() == null);
            if (hasUnsafeOrigin) {
                throw new IllegalArgumentException("Wildcard CORS origins are not permitted");
            }
        }

        public void validateKeyMaterial() {
            validateBase64Key(credentialHmacKey, "credentialHmacKey");
            validateBase64Key(auditHmacKey, "auditHmacKey");
            if (credentialHmacKey.equals(auditHmacKey)) {
                throw new IllegalArgumentException("Credential and audit HMAC keys must be distinct");
            }
        }

        private static void validateBase64Key(String encoded, String name) {
            try {
                byte[] decoded = java.util.Base64.getDecoder().decode(encoded);
                if (decoded.length < 32) {
                    throw new IllegalArgumentException(name + " must decode to at least 32 bytes");
                }
            } catch (IllegalArgumentException exception) {
                if (exception.getMessage() != null && exception.getMessage().contains("at least 32 bytes")) {
                    throw exception;
                }
                throw new IllegalArgumentException(name + " must be valid Base64", exception);
            }
        }
    }

    /**
     * Internal operator administration settings.
     *
     * <p>A separate record rather than more fields on Security, so a reader cannot
     * assume operator configuration is governed by the same rules as developer security.
     */
    public record Platform(@NotBlank String operatorHmacKey) {
    }
}
