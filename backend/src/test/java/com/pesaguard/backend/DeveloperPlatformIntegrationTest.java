package com.pesaguard.backend;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeveloperPlatformIntegrationTest {

    private static final String CREDENTIAL_KEY = base64("integration-credential-key-32-bytes!!");
    private static final String AUDIT_KEY = base64("integration-audit-key-32-bytes!!!!!!");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-bookworm");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("pesaguard.security.credential-hmac-key", () -> CREDENTIAL_KEY);
        registry.add("pesaguard.security.audit-hmac-key", () -> AUDIT_KEY);
        registry.add("pesaguard.security.allowed-origins", () -> "https://developers.pesaguard.victorkipruto.com");
        registry.add("pesaguard.security.registration-enabled", () -> "true");
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void registrationCreatesTenantScopedResourcesAndOneTimeApiKey() throws Exception {
        String email = uniqueEmail();
        String token = register(email);
        String projectId = createProject(token);
        String environmentId = createEnvironment(token, projectId);

        String keyResponse = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/environments/{environmentId}/api-keys", projectId, environmentId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"local-worker\",\"scopes\":[\"projects:read\",\"environments:read\"],\"expiresIn\":\"PT24H\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.key", containsString("pgk_")))
                .andReturn().getResponse().getContentAsString();
        String keyId = jsonValue(keyResponse, "id");

        mockMvc.perform(get("/api/v1/projects/{projectId}/environments/{environmentId}/api-keys", projectId, environmentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].key").doesNotExist())
                .andExpect(jsonPath("$.data[0].prefix").exists());
        mockMvc.perform(delete("/api/v1/projects/{projectId}/environments/{environmentId}/api-keys/{keyId}",
                        projectId, environmentId, keyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/audit-events/verify").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true));
    }

    @Test
    void crossTenantProjectLookupIsNotFound() throws Exception {
        String firstToken = register(uniqueEmail());
        String projectId = createProject(firstToken);
        String secondToken = register(uniqueEmail());

        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId)
                        .header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void logoutInvalidatesBearerSession() throws Exception {
        String token = register(uniqueEmail());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/auth/session")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void organizationLifecycleIsTenantScopedAndAudited() throws Exception {
        String token = register(uniqueEmail());

        mockMvc.perform(get("/api/v1/organization").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.type").value("DEVELOPER"))
                .andExpect(jsonPath("$.data.slug").exists());

        mockMvc.perform(patch("/api/v1/organization")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed Org\",\"type\":\"ENTERPRISE\",\"metadata\":{\"region\":\"ke\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Renamed Org"))
                .andExpect(jsonPath("$.data.metadata.region").value("ke"));

        mockMvc.perform(post("/api/v1/organization/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"verification-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verificationReference").value("verification-1"));

        mockMvc.perform(post("/api/v1/organization/suspend").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        mockMvc.perform(get("/api/v1/organization").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invitationsGrantMembershipOnceAndCannotBeReused() throws Exception {
        String ownerToken = register(uniqueEmail());
        String inviteeEmail = uniqueEmail();
        String invitationToken = createInvitation(ownerToken, inviteeEmail);

        mockMvc.perform(post("/api/v1/organization/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + inviteeEmail + "\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_ALREADY_PENDING"));

        String issuedToken = acceptInvitation(invitationToken, inviteeEmail);

        mockMvc.perform(post("/api/v1/organization/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody(invitationToken, inviteeEmail)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_INVITATION"));

        mockMvc.perform(get("/api/v1/organization/members").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));
        mockMvc.perform(get("/api/v1/organization/members").header("Authorization", "Bearer " + issuedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));
        mockMvc.perform(get("/api/v1/organization/security-settings").header("Authorization", "Bearer " + issuedToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void memberSuspensionRevokesSessionsAndIsRecordedInHistory() throws Exception {
        String ownerToken = register(uniqueEmail());
        String inviteeEmail = uniqueEmail();
        String memberToken = acceptInvitation(createInvitation(ownerToken, inviteeEmail), inviteeEmail);
        String membershipId = developerMembershipId(ownerToken);

        mockMvc.perform(patch("/api/v1/organization/members/{membershipId}/status", membershipId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"reason\":\"policy review\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        mockMvc.perform(get("/api/v1/auth/session").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/organization/members/{membershipId}/history", membershipId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[1].toStatus").value("SUSPENDED"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + inviteeEmail + "\",\"password\":\"correct horse battery staple\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void multiOrganizationAccountsMustSelectAnOrganizationAtLogin() throws Exception {
        String email = uniqueEmail();
        String token = register(email);
        String firstOrganizationId = organizationId(token);
        String secondOrganizationId = jsonValue(mockMvc.perform(post("/api/v1/organization")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Second Org\",\"type\":\"DEVELOPER\",\"metadata\":{}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORGANIZATION_SELECTION_REQUIRED"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\","
                                + "\"organizationId\":\"" + secondOrganizationId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organization.id").value(secondOrganizationId));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\","
                                + "\"organizationId\":\"" + firstOrganizationId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organization.id").value(firstOrganizationId));
    }

    @Test
    void securitySettingsRejectUnsupportedProviders() throws Exception {
        String token = register(uniqueEmail());

        mockMvc.perform(put("/api/v1/organization/security-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowedAuthMethods\":[\"PASSWORD\",\"OIDC\"],\"sessionTtlMinutes\":120,"
                                + "\"idleTimeoutMinutes\":60,\"maxSessions\":5,\"credentialMinLength\":14,"
                                + "\"credentialMaxLength\":72,\"mfaRequired\":false,\"ipAllowlist\":[],"
                                + "\"securityEventTypes\":[\"LOGIN_FAILURE\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_AUTH_METHOD"));

        mockMvc.perform(put("/api/v1/organization/security-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowedAuthMethods\":[\"PASSWORD\"],\"sessionTtlMinutes\":120,"
                                + "\"idleTimeoutMinutes\":60,\"maxSessions\":5,\"credentialMinLength\":14,"
                                + "\"credentialMaxLength\":72,\"mfaRequired\":true,\"ipAllowlist\":[],"
                                + "\"securityEventTypes\":[\"LOGIN_FAILURE\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_SECURITY_POLICY"));

        mockMvc.perform(put("/api/v1/organization/security-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowedAuthMethods\":[\"PASSWORD\"],\"sessionTtlMinutes\":120,"
                                + "\"idleTimeoutMinutes\":60,\"maxSessions\":5,\"credentialMinLength\":14,"
                                + "\"credentialMaxLength\":72,\"mfaRequired\":false,\"ipAllowlist\":[],"
                                + "\"securityEventTypes\":[\"LOGIN_FAILURE\",\"MEMBERSHIP_CHANGED\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionTtlMinutes").value(120))
                .andExpect(jsonPath("$.data.credentialMinLength").value(14));
    }

    @Test
    void membershipOperationsAreIsolatedPerTenant() throws Exception {
        String firstOwnerToken = register(uniqueEmail());
        String inviteeEmail = uniqueEmail();
        acceptInvitation(createInvitation(firstOwnerToken, inviteeEmail), inviteeEmail);
        String membershipId = developerMembershipId(firstOwnerToken);

        String secondOwnerToken = register(uniqueEmail());
        mockMvc.perform(patch("/api/v1/organization/members/{membershipId}/status", membershipId)
                        .header("Authorization", "Bearer " + secondOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REVOKED\",\"reason\":\"cross tenant attempt\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/organization/members/{membershipId}/history", membershipId)
                        .header("Authorization", "Bearer " + secondOwnerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void repeatedInvalidLoginIsRateLimited() throws Exception {
        String email = uniqueEmail();
        String body = "{\"email\":\"" + email + "\",\"password\":\"wrong-password-value\"}";
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    private String register(String email) throws Exception {
        String organization = "Acme " + email.substring(0, email.indexOf('@'));
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\","
                                + "\"displayName\":\"Test User\",\"organizationName\":\"" + organization + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andReturn().getResponse().getContentAsString();
        return jsonValue(response, "accessToken");
    }

    private String createProject(String token) throws Exception {
        String slug = "project-" + UUID.randomUUID().toString().substring(0, 8);
        String response = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Payments\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonValue(response, "id");
    }

    private String createEnvironment(String token, String projectId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/projects/{projectId}/environments", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sandbox\",\"type\":\"SANDBOX\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonValue(response, "id");
    }

    private String createInvitation(String ownerToken, String inviteeEmail) throws Exception {
        String response = mockMvc.perform(post("/api/v1/organization/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + inviteeEmail + "\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        return jsonValue(response, "token");
    }

    private String acceptInvitation(String invitationToken, String inviteeEmail) throws Exception {
        String response = mockMvc.perform(post("/api/v1/organization/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody(invitationToken, inviteeEmail)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return jsonValue(response, "accessToken");
    }

    private String developerMembershipId(String ownerToken) throws Exception {
        String response = mockMvc.perform(get("/api/v1/organization/members")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int roleIndex = response.indexOf("\"role\":\"DEVELOPER\"");
        if (roleIndex < 0) {
            throw new AssertionError("No developer membership was created: " + response);
        }
        int marker = response.lastIndexOf("\"id\":\"", roleIndex);
        int start = marker + "\"id\":\"".length();
        return response.substring(start, response.indexOf('"', start));
    }

    private String organizationId(String token) throws Exception {
        String response = mockMvc.perform(get("/api/v1/auth/session")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonValue(response, "organizationId");
    }

    private static String acceptBody(String invitationToken, String inviteeEmail) {
        return "{\"token\":\"" + invitationToken + "\",\"email\":\"" + inviteeEmail
                + "\",\"password\":\"correct horse battery staple\",\"displayName\":\"Invited User\"}";
    }

    private static String uniqueEmail() {
        return "test-" + UUID.randomUUID() + "@example.com";
    }

    private static String jsonValue(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
