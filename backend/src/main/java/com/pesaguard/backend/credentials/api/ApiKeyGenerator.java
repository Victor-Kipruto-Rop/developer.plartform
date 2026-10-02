package com.pesaguard.backend.credentials.api;

import java.time.Duration;
import java.util.Set;

import com.pesaguard.backend.security.credentials.CredentialCryptoService;

import org.springframework.stereotype.Component;

@Component
public class ApiKeyGenerator {

    private final CredentialCryptoService crypto;

    public ApiKeyGenerator(CredentialCryptoService crypto) {
        this.crypto = crypto;
    }

    public GeneratedKey generate() {
        String secret = "pgk_" + crypto.randomToken(32);
        return new GeneratedKey(secret, secret.substring(0, 16));
    }

    public String hash(String rawKey) {
        return crypto.hmacSha256(rawKey);
    }

    public record GeneratedKey(String rawKey, String prefix) {
    }
}
