package com.pesaguard.backend.security.authentication;

import java.time.Instant;

public record AuthenticationResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        SessionUserResponse user,
        SessionOrganizationResponse organization) {
}
