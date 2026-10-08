package com.pesaguard.backend.security.authentication;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/v1/auth/change-password}.
 *
 * <p>The current password is required even though the caller is authenticated, so
 * that a stolen access token alone cannot lock the owner out.
 */
public record ChangePasswordRequest(
        @NotBlank @Size(max = 200) String currentPassword,
        @NotBlank @Size(max = 200) String newPassword) {
}