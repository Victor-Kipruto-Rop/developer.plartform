package com.pesaguard.backend.security.authentication;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {

    private final RegistrationService registrationService;
    private final LoginService loginService;

    public AuthenticationController(RegistrationService registrationService, LoginService loginService) {
        this.registrationService = registrationService;
        this.loginService = loginService;
    }

    @PostMapping("/register")
    ResponseEntity<ApiResponse<AuthenticationResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(registrationService.register(request)));
    }

    @PostMapping("/login")
    ResponseEntity<ApiResponse<AuthenticationResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(loginService.login(request, servletRequest.getRemoteAddr())));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser principal) {
        loginService.logout(principal.userId(), principal.organizationId(), principal.sessionId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/session")
    ApiResponse<CurrentSessionResponse> currentSession(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(new CurrentSessionResponse(
                principal.sessionId().toString(),
                new SessionUserResponse(principal.userId(), principal.email(), principal.displayName()),
                principal.organizationId(),
                principal.authorities()));
    }
}
