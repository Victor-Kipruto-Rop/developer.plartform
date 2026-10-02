package com.pesaguard.backend.security.authentication;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 128) String password,
        @NotBlank @Size(min = 2, max = 120) String displayName,
        @NotBlank @Size(min = 2, max = 120) String organizationName) {
}
