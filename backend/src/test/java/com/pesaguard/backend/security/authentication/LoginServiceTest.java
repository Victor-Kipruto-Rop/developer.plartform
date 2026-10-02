package com.pesaguard.backend.security.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.UnauthorizedException;
import com.pesaguard.backend.config.ApplicationProperties;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.organization.application.OrganizationSecuritySettingsService;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.domain.OrganizationSecuritySettings;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.sessions.SessionService;
import com.pesaguard.backend.security.throttling.RequestThrottleService;

class LoginServiceTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final OrganizationMembershipRepository membershipRepository = mock(OrganizationMembershipRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final RequestThrottleService throttleService = mock(RequestThrottleService.class);
    private final OrganizationSecuritySettingsService securitySettingsService =
            mock(OrganizationSecuritySettingsService.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final AuditService auditService = mock(AuditService.class);

    private LoginService loginService;

    @BeforeEach
    void setUp() {
        SecretKeySpec credentialKey =
                new SecretKeySpec("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        CredentialCryptoService cryptoService = new CredentialCryptoService(credentialKey, new SecureRandom());
        ApplicationProperties properties = new ApplicationProperties(new ApplicationProperties.Security(
                List.of(java.net.URI.create("https://developers.pesaguard.victorkipruto.com")),
                Duration.ofHours(8),
                "Y3JlZGVudGlhbC1rZXktMzItYnl0ZXMh",
                "YXVkaXQta2V5LTMyLWJ5dGVzISEhISE=",
                true,
                4,
                3,
                10,
                Duration.ofMinutes(15),
                Duration.ofDays(7),
                30,
                Duration.ofMinutes(15)),
                new ApplicationProperties.Platform(
                        "b3BlcmF0b3Ita2V5LTMyLWJ5dGVzISEhISEh"));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-placeholder");
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(sessionService.issue(any(), any(Duration.class), anyInt()))
                .thenReturn(new SessionService.IssuedSession("token", UUID.randomUUID(), NOW.plusSeconds(600)));
        loginService = new LoginService(
                membershipRepository, passwordEncoder, cryptoService, throttleService, securitySettingsService,
                new PasswordPolicy(), sessionService, auditService, properties, credentialKey);
    }

    private OrganizationMembership membership() {
        UserAccount user = UserAccount.create("person@example.com", "Person", "encoded-hash");
        Organization organization = Organization.create("Acme", "acme-" + UUID.randomUUID(), user.getId(), NOW);
        return OrganizationMembership.owner(organization, user);
    }

    private void givenMemberships(List<OrganizationMembership> memberships) {
        when(membershipRepository.findAllActiveByEmail("person@example.com")).thenReturn(memberships);
    }

    private void givenSettings(OrganizationMembership membership, int sessionTtlMinutes, int maxSessions) {
        OrganizationSecuritySettings settings = OrganizationSecuritySettings.defaults(
                membership.getOrganization().getId(), NOW);
        settings.update(
                "PASSWORD", sessionTtlMinutes, 120, maxSessions, 12, 72, false,
                "", "LOGIN_FAILURE", null, NOW);
        when(securitySettingsService.getOrCreate(membership.getOrganization().getId())).thenReturn(settings);
    }

    @Test
    void singleOrganizationLoginIssuesSessionWithOrganizationPolicy() {
        OrganizationMembership membership = membership();
        givenMemberships(List.of(membership));
        givenSettings(membership, 30, 3);

        AuthenticationResponse response = loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10");

        assertThat(response.organization().id()).isEqualTo(membership.getOrganization().getId());
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(sessionService).issue(eq(membership), ttl.capture(), eq(3));
        assertThat(ttl.getValue()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void multipleOrganizationsRequireExplicitSelection() {
        givenMemberships(List.of(membership(), membership()));

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("more than one organization");
        verify(sessionService, never()).issue(any(), any(Duration.class), anyInt());
    }

    @Test
    void selectedOrganizationMustBeAnActiveMembership() {
        OrganizationMembership first = membership();
        OrganizationMembership second = membership();
        givenMemberships(List.of(first, second));
        givenSettings(second, 480, 10);

        AuthenticationResponse response = loginService.login(
                new LoginRequest("person@example.com", PASSWORD, second.getOrganization().getId()), "203.0.113.10");

        assertThat(response.organization().id()).isEqualTo(second.getOrganization().getId());
        verify(sessionService).issue(eq(second), any(Duration.class), eq(10));
    }

    @Test
    void unknownOrganizationSelectionDoesNotRevealMembership() {
        givenMemberships(List.of(membership()));

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, UUID.randomUUID()), "203.0.113.10"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("incorrect");
        verify(throttleService).recordFailure(eq("account"), anyString(), anyInt());
        verify(sessionService, never()).issue(any(), any(Duration.class), anyInt());
    }

    @Test
    void ipRestrictionsAreEnforcedBeforeSessionIssuance() {
        OrganizationMembership membership = membership();
        givenMemberships(List.of(membership));
        givenSettings(membership, 480, 10);
        org.mockito.Mockito.doThrow(new BusinessException(
                org.springframework.http.HttpStatus.FORBIDDEN, "IP_NOT_ALLOWED", "not permitted"))
                .when(securitySettingsService).assertIpAllowed(any(), any());

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not permitted");
        verify(sessionService, never()).issue(any(), any(Duration.class), anyInt());
    }

    @Test
    void passwordAuthenticationMustBeEnabledForTheOrganization() {
        OrganizationMembership membership = membership();
        givenMemberships(List.of(membership));
        givenSettings(membership, 480, 10);
        org.mockito.Mockito.doThrow(new BusinessException(
                org.springframework.http.HttpStatus.FORBIDDEN, "AUTH_METHOD_DISABLED", "disabled"))
                .when(securitySettingsService).assertPasswordLoginAllowed(any());

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("disabled");
        verify(sessionService, never()).issue(any(), any(Duration.class), anyInt());
    }

    @Test
    void wrongPasswordIsRateLimitedAndRejected() {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        givenMemberships(List.of(membership()));

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", "wrong password value", null), "203.0.113.10"))
                .isInstanceOf(UnauthorizedException.class);
        verify(throttleService).recordFailure(eq("account"), anyString(), anyInt());
        verify(throttleService).recordFailure(eq("ip"), anyString(), anyInt());
        verify(sessionService, never()).issue(any(), any(Duration.class), anyInt());
    }

    @Test
    void loginIsAuditedAgainstTheAuthenticatedOrganization() {
        OrganizationMembership membership = membership();
        givenMemberships(List.of(membership));
        givenSettings(membership, 480, 10);

        loginService.login(new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10");

        verify(auditService).append(
                eq(membership.getOrganization().getId()), eq(membership.getUser().getId()),
                eq("auth.login.succeeded"), eq("session"), anyString(), any(), eq(Map.of()));
    }
}