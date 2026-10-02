package com.pesaguard.backend.config;

import java.security.SecureRandom;
import java.time.Clock;

import javax.crypto.SecretKey;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class ApplicationConfig {

    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder(ApplicationProperties properties) {
        return new BCryptPasswordEncoder(properties.security().bcryptStrength());
    }

    @Bean
    SecretKey credentialHmacKey(ApplicationProperties properties) {
        properties.security().validateKeyMaterial();
        byte[] key = java.util.Base64.getDecoder().decode(properties.security().credentialHmacKey());
        return new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256");
    }

    /**
     * Key for internal operator tokens.
     *
     * <p><b>Deliberately a separate key from the credential and audit keys.</b>
     * Sharing one would mean a developer API key presented as an operator token is
     * a matter of formatting, and the entire separation between the developer API
     * and internal administration rests on those tokens being unrelated.
     *
     * <p>Validates at startup rather than lazily: an operator key that is missing or
     * wrong must stop the deployment, not surface as an authentication failure
     * during an incident.
     */
    @Bean
    SecretKey operatorHmacKey(ApplicationProperties properties) {
        String encoded = properties.platform().operatorHmacKey();
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(
                    "PESAGUARD_OPERATOR_HMAC_KEY is required for internal administration");
        }
        byte[] key;
        try {
            key = java.util.Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException(
                    "PESAGUARD_OPERATOR_HMAC_KEY must be base64", malformed);
        }
        if (key.length < 32) {
            throw new IllegalStateException(
                    "PESAGUARD_OPERATOR_HMAC_KEY must decode to at least 32 bytes");
        }
        return new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    SecretKey auditHmacKey(ApplicationProperties properties) {
        byte[] key = java.util.Base64.getDecoder().decode(properties.security().auditHmacKey());
        return new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256");
    }
}
