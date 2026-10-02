package com.pesaguard.backend.security.authentication;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 128) String password,
        UUID organizationId) {

    public LoginRequest(String email, String password) {
        this(email, password, null);
    }
}
