package com.pesaguard.backend.organization.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptInvitationRequest(
        @NotBlank @Size(max = 512) String token,
        @NotBlank @Email @Size(max = 320) String email,
        @Size(min = 2, max = 120) String displayName,
        @NotBlank @Size(min = 12, max = 128) String password) {
}