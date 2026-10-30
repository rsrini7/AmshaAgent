package com.amsha.app.connector.github;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.amsha.app.security.ActiveWorkspaceFilter;
import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.common.config.ConfigKeys;
import com.amsha.core.audit.AuditService;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WebSessionStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

@QuarkusTest
@TestProfile(GitHubReadmeConnectorDisabledIT.DisabledProfile.class)
class GitHubReadmeConnectorDisabledIT {
    private static final String COMMIT = "1111111111111111111111111111111111111111";
    private static final String SENTINEL = "disabled connector indexed content sentinel";
    private static final GitHubReadmeConnectionSettings SETTINGS = GitHubReadmeSourcePolicy.firstConnection();

    @InjectMock
    GitHubReadmeAppClient appClient;

    @InjectMock
    AuditService auditService;

    @Inject
    IdentityStorePort identityStore;

    @Inject
    WebSessionStorePort webSessionStore;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @Inject
    GitHubReadmeIndexStore indexStore;

    @Inject
    GitHubReadmeConnectorService connectorService;

    @Inject
    GitHubReadmeContextEnricher contextEnricher;

    private String owner;
    private String workspace;
    private String sessionToken;
    private String csrfToken;

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(ConfigKeys.GITHUB_DOCS_CONNECTOR_ENABLED_PROPERTY, "false");
        }
    }

    @BeforeEach
    void seedOwner() {
        Mockito.reset(appClient, auditService);
        assertFalse(connectorService.enabled());
        String suffix = UUID.randomUUID().toString();
        owner = "github-disabled-owner-" + suffix;
        workspace = "github-disabled-workspace-" + suffix;
        Instant now = Instant.now();
        identityStore.saveUser(new IdentityUserDto(owner, owner, "ACTIVE", now, now));
        identityStore.saveWorkspace(new IdentityWorkspaceDto(workspace, workspace, owner, now, now));
        identityStore.saveMembership(new WorkspaceMembershipDto(workspace, owner, "OWNER", "ACTIVE", 1, now, now));
        sessionToken = "sess-" + UUID.randomUUID();
        csrfToken = "csrf-" + UUID.randomUUID();
        webSessionStore.save(new WebSessionDto(
                UUID.randomUUID().toString(),
                owner,
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
    }

    @AfterEach
    void removeTestData() {
        connectorService.disconnect(owner, workspace);
        webSessionStore.deleteByUserId(owner);
        identityStore.deleteMembership(workspace, owner);
    }

    @Test
    void disabledConnectorBlocksApiAndPromptRetrievalButAllowsDisconnect() throws Exception {
        String namespace = GitHubReadmeIndexStore.namespace(owner, workspace);
        indexStore.storeSnapshot(
                namespace,
                owner,
                SETTINGS,
                new GitHubReadmeSnapshot(
                        42,
                        "rsrini7/AmshaAgent",
                        "main",
                        COMMIT,
                        List.of(new GitHubReadmeDocumentSnapshot("README.md", SENTINEL, COMMIT))));

        request()
                .contentType("application/json")
                .body("{\"repository\":\"rsrini7/AmshaAgent\"," + "\"allowedPaths\":[\"README.md\",\"docs/**/*.md\"]}")
                .when()
                .post("/api/v1/connectors/github-readme/connect")
                .then()
                .statusCode(503)
                .body("error", equalTo("connector_disabled"));

        request()
                .when()
                .post("/api/v1/connectors/github-readme/refresh")
                .then()
                .statusCode(503)
                .body("error", equalTo("connector_disabled"));

        request()
                .when()
                .get("/api/v1/connectors/github-readme/status")
                .then()
                .statusCode(200)
                .body("state", equalTo("DISABLED"))
                .body("indexed", equalTo(false));

        String prompt = WorkspaceContext.callWithActiveWorkspace(
                workspace, () -> contextEnricher.enrich("disabled-session", owner, SENTINEL, "base prompt"));
        assertEquals("base prompt", prompt);
        verify(appClient, never()).fetchCurrent(anyString(), any());

        request()
                .when()
                .delete("/api/v1/connectors/github-readme")
                .then()
                .statusCode(200)
                .body("state", equalTo("DISCONNECTED"));
        assertTrue(indexStore.readManifest(namespace, owner).isEmpty());
        verify(appClient, never()).fetchCurrent(anyString(), any());
    }

    private RequestSpecification request() {
        return RestAssured.given()
                .cookie("AMSHA_SESSION_ID", sessionToken)
                .header("X-CSRF-Token", csrfToken)
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, workspace);
    }
}
