package com.amsha.app.connector.github;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.common.config.ConfigKeys;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WebSessionStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

@QuarkusTest
@TestProfile(GitHubAppCredentialResourceIT.DisabledProfile.class)
class GitHubAppCredentialResourceIT {

    @InjectMock
    SecretVault secretVault;

    @InjectMock
    AuditService auditService;

    @Inject
    IdentityStorePort identityStore;

    @Inject
    WebSessionStorePort webSessionStore;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    private String adminUser;
    private String adminSession;
    private String adminCsrf;
    private String regularUser;
    private String regularSession;
    private String regularCsrf;
    private String adminWorkspace;
    private String regularWorkspace;

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(ConfigKeys.GITHUB_DOCS_CONNECTOR_ENABLED_PROPERTY, "false");
        }
    }

    @BeforeEach
    void seedAuthenticatedUsers() {
        Mockito.reset(secretVault, auditService);
        String suffix = UUID.randomUUID().toString();
        adminUser = "credential-admin-" + suffix;
        regularUser = "credential-user-" + suffix;
        adminWorkspace = "credential-admin-workspace-" + suffix;
        regularWorkspace = "credential-user-workspace-" + suffix;
        seedUserAndWorkspace(adminUser, adminWorkspace, "OWNER");
        seedUserAndWorkspace(regularUser, regularWorkspace, "MEMBER");
        SessionTokens adminTokens = createSession(adminUser);
        adminSession = adminTokens.sessionToken();
        adminCsrf = adminTokens.csrfToken();
        SessionTokens regularTokens = createSession(regularUser);
        regularSession = regularTokens.sessionToken();
        regularCsrf = regularTokens.csrfToken();
    }

    @AfterEach
    void removeTestSessionsAndMemberships() {
        webSessionStore.deleteByUserId(adminUser);
        webSessionStore.deleteByUserId(regularUser);
        identityStore.deleteMembership(adminWorkspace, adminUser);
        identityStore.deleteMembership(regularWorkspace, regularUser);
    }

    @Test
    void uploadsOnlyValidatedKeyAuditsBeforeVaultMutationAndNeverReturnsKeyMaterial() throws Exception {
        byte[] pem = privateKeyPem();

        Response response = adminRequest()
                .contentType("application/octet-stream")
                .body(pem)
                .put("/api/v1/admin/connectors/github-readme/app-credential");
        assertEquals(200, response.statusCode());
        assertFalse(response.jsonPath().getBoolean("enabled"));
        assertTrue(response.jsonPath().getBoolean("configured"));
        assertFalse(response.asString().contains(new String(pem, StandardCharsets.US_ASCII)));

        ArgumentCaptor<byte[]> storedBytes = ArgumentCaptor.forClass(byte[].class);
        InOrder order = inOrder(auditService, secretVault);
        order.verify(auditService)
                .recordMandatory(
                        isNull(),
                        eq(adminUser),
                        eq("github-readme-credential-admin"),
                        eq(SecurityEventType.VAULT_ACCESS),
                        eq("operation=store keyName=" + VaultConstants.GITHUB_APP_PRIVATE_KEY));
        order.verify(secretVault).storeSystemKey(eq(VaultConstants.GITHUB_APP_PRIVATE_KEY), storedBytes.capture());
        assertTrue(allZero(storedBytes.getValue()));
    }

    @Test
    void internalCredentialStatusReportsOnlyPresenceWithoutCaching() {
        Mockito.when(secretVault.systemKeyExists(VaultConstants.GITHUB_APP_PRIVATE_KEY))
                .thenReturn(true);

        Response response = adminRequest().get("/api/v1/admin/connectors/github-readme/app-credential");

        assertEquals(200, response.statusCode());
        assertFalse(response.jsonPath().getBoolean("enabled"));
        assertTrue(response.jsonPath().getBoolean("configured"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertFalse(response.asString().contains(VaultConstants.GITHUB_APP_PRIVATE_KEY));
        verify(secretVault).systemKeyExists(VaultConstants.GITHUB_APP_PRIVATE_KEY);
    }

    @Test
    void internalCredentialStatusReportsMissingKey() {
        Mockito.when(secretVault.systemKeyExists(VaultConstants.GITHUB_APP_PRIVATE_KEY))
                .thenReturn(false);

        Response response = adminRequest().get("/api/v1/admin/connectors/github-readme/app-credential");

        assertEquals(200, response.statusCode());
        assertFalse(response.jsonPath().getBoolean("enabled"));
        assertFalse(response.jsonPath().getBoolean("configured"));
    }

    @Test
    void rejectsMalformedCredentialWithoutMutation() {
        adminRequest()
                .contentType("application/octet-stream")
                .body("not-a-private-key".getBytes(StandardCharsets.US_ASCII))
                .when()
                .put("/api/v1/admin/connectors/github-readme/app-credential")
                .then()
                .statusCode(400)
                .body(not(containsString("not-a-private-key")));

        verify(secretVault, never()).storeSystemKey(any(String.class), any(byte[].class));
    }

    @Test
    void nonInternalUserCannotStoreOrRemoveAppCredentials() {
        regularRequest()
                .contentType("application/octet-stream")
                .body("private-key-placeholder".getBytes(StandardCharsets.US_ASCII))
                .when()
                .put("/api/v1/admin/connectors/github-readme/app-credential")
                .then()
                .statusCode(403)
                .body(not(containsString("private-key-placeholder")));
        regularRequest()
                .when()
                .delete("/api/v1/admin/connectors/github-readme/app-credential")
                .then()
                .statusCode(403);
        regularRequest()
                .when()
                .get("/api/v1/admin/connectors/github-readme/app-credential")
                .then()
                .statusCode(403);

        verify(secretVault, never()).storeSystemKey(any(String.class), any(byte[].class));
        verify(secretVault, never()).removeSystemKey(any(String.class));
        verify(secretVault, never()).systemKeyExists(any(String.class));
    }

    private RequestSpecification adminRequest() {
        return authenticatedRequest(adminSession, adminCsrf);
    }

    private RequestSpecification regularRequest() {
        return authenticatedRequest(regularSession, regularCsrf);
    }

    private static RequestSpecification authenticatedRequest(String sessionToken, String csrfToken) {
        return given().cookie("AMSHA_SESSION_ID", sessionToken).header("X-CSRF-Token", csrfToken);
    }

    private void seedUserAndWorkspace(String userId, String workspaceId, String role) {
        Instant now = Instant.now();
        identityStore.saveUser(new IdentityUserDto(userId, userId, "ACTIVE", now, now));
        identityStore.saveWorkspace(new IdentityWorkspaceDto(workspaceId, workspaceId, userId, now, now));
        identityStore.saveMembership(new WorkspaceMembershipDto(workspaceId, userId, role, "ACTIVE", 1, now, now));
    }

    private SessionTokens createSession(String userId) {
        String sessionToken = "sess-" + UUID.randomUUID();
        String csrfToken = "csrf-" + UUID.randomUUID();
        Instant now = Instant.now();
        webSessionStore.save(new WebSessionDto(
                UUID.randomUUID().toString(),
                userId,
                apiKeyRegistry.computeDigest(sessionToken),
                apiKeyRegistry.computeDigest(csrfToken),
                now.plusSeconds(3600),
                now.plusSeconds(7200),
                null,
                null,
                "{}",
                1,
                now,
                now));
        return new SessionTokens(sessionToken, csrfToken);
    }

    private static byte[] privateKeyPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        return ("-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private record SessionTokens(String sessionToken, String csrfToken) {}
}
