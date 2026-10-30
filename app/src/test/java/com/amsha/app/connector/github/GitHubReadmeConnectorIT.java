package com.amsha.app.connector.github;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.amsha.app.security.ActiveWorkspaceFilter;
import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.common.config.ConfigKeys;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.util.HttpConstants;
import com.amsha.persistence.api.AccessRoles;
import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ApiCredentialStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WebSessionStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;

@QuarkusTest
@TestProfile(GitHubReadmeConnectorIT.EnabledProfile.class)
class GitHubReadmeConnectorIT {

    private static final String PUBLIC_SENTINEL = "profile-public-content-sentinel";
    private static final String COMMIT_ONE = "1111111111111111111111111111111111111111";
    private static final String COMMIT_TWO = "2222222222222222222222222222222222222222";
    private static final GitHubReadmeConnectionSettings SETTINGS = GitHubReadmeSourcePolicy.firstConnection();
    private static final String SETTINGS_BODY =
            "{\"repository\":\"rsrini7/AmshaAgent\"," + "\"allowedPaths\":[\"README.md\",\"docs/**/*.md\"]}";

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
    ApiCredentialStorePort apiCredentialStore;

    @ConfigProperty(name = "amsha.api.keys")
    Map<String, String> configuredApiKeys;

    @Inject
    GitHubReadmeContextEnricher contextEnricher;

    @Inject
    GitHubReadmeConnectorService connectorService;

    private String owner;
    private String ownerWorkspace;
    private String otherWorkspace;
    private String memberWorkspace;
    private String deleteWorkspace;
    private String bodyWorkspace;
    private String ownerSession;
    private String ownerCsrf;
    private String member;
    private String memberSession;
    private String memberCsrf;

    public static class EnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(ConfigKeys.GITHUB_DOCS_CONNECTOR_ENABLED_PROPERTY, "true");
        }
    }

    @BeforeEach
    void resetAndSeedSession() {
        assertTrue(connectorService.enabled());
        Mockito.reset(appClient, auditService);
        String suffix = UUID.randomUUID().toString();
        owner = "connector-owner-" + suffix;
        ownerWorkspace = "connector-owner-workspace-" + suffix;
        otherWorkspace = "connector-other-workspace-" + suffix;
        memberWorkspace = "connector-member-workspace-" + suffix;
        deleteWorkspace = "connector-delete-workspace-" + suffix;
        bodyWorkspace = "connector-body-workspace-" + suffix;
        createUser(owner);
        createOwnerWorkspace(ownerWorkspace, owner);
        createOwnerWorkspace(memberWorkspace, owner);
        createOwnerWorkspace(deleteWorkspace, owner);
        createOwnerWorkspace(bodyWorkspace, owner);
        SessionTokens ownerTokens = createSession(owner);
        ownerSession = ownerTokens.sessionToken();
        ownerCsrf = ownerTokens.csrfToken();
        clearInvocations(appClient, auditService);
    }

    @AfterEach
    void removeTestIndexAndSessions() {
        for (String workspaceId : workspaceIds()) {
            connectorService.disconnect(owner, workspaceId);
            identityStore.deleteMembership(workspaceId, owner);
        }
        if (member != null) {
            webSessionStore.deleteByUserId(member);
            identityStore.deleteMembership(memberWorkspace, member);
        }
        webSessionStore.deleteByUserId(owner);
    }

    @Test
    void ownerRefreshesCurrentCommitAndOnlyTheirConnectedWorkspaceCanRetrieveIt() throws Exception {
        GitHubReadmeSnapshot first = snapshot(
                COMMIT_ONE,
                "## About\n" + PUBLIC_SENTINEL + " aircraft telemetry profile.\n\n## Work\nOther profile text.");
        GitHubReadmeSnapshot second = snapshot(COMMIT_TWO, "## About\nUpdated telemetry profile version.");
        when(appClient.fetchCurrent(owner, SETTINGS)).thenReturn(first, first, second);

        connectRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, ownerWorkspace)
                .when()
                .post("/api/v1/connectors/github-readme/connect")
                .then()
                .statusCode(200)
                .body("state", equalTo("INDEXED"))
                .body("indexed", equalTo(true))
                .body("repositoryId", equalTo(42))
                .body("repository", equalTo("rsrini7/AmshaAgent"))
                .body("documents.size()", equalTo(2))
                .body("documents[0].path", equalTo("README.md"))
                .body("documents[0].commitSha", equalTo(COMMIT_ONE))
                .body("documents[0].blobSha", equalTo(COMMIT_ONE))
                .body("documents[1].path", equalTo("docs/architecture.md"))
                .body("documents[1].commitSha", equalTo(COMMIT_ONE))
                .body("documents[1].blobSha", equalTo(COMMIT_ONE))
                .body(not(containsString(PUBLIC_SENTINEL)));

        assertTrue(enrich(ownerWorkspace, owner, "find my aircraft telemetry profile")
                .contains(PUBLIC_SENTINEL));
        assertFalse(enrich(ownerWorkspace, "another-owner", "find my aircraft telemetry profile")
                .contains(PUBLIC_SENTINEL));
        assertFalse(enrich(otherWorkspace, owner, "find my aircraft telemetry profile")
                .contains(PUBLIC_SENTINEL));
        assertFalse(enrich(memberWorkspace, owner, "find my aircraft telemetry profile")
                .contains(PUBLIC_SENTINEL));
        ownerRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, memberWorkspace)
                .when()
                .get("/api/v1/connectors/github-readme/status")
                .then()
                .statusCode(200)
                .body("state", equalTo("NOT_INDEXED"));
        String architectureContext = enrich(ownerWorkspace, owner, "architecture governance run guide");
        assertTrue(architectureContext.contains("Source: docs/architecture.md (commit " + COMMIT_ONE + ")"));
        assertTrue(architectureContext.contains("architecture-doc-only-sentinel"));

        ownerRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, ownerWorkspace)
                .when()
                .post("/api/v1/connectors/github-readme/refresh")
                .then()
                .statusCode(200)
                .body("commitSha", equalTo(COMMIT_ONE))
                .body(not(containsString(PUBLIC_SENTINEL)));

        ownerRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, ownerWorkspace)
                .when()
                .post("/api/v1/connectors/github-readme/refresh")
                .then()
                .statusCode(200)
                .body("commitSha", equalTo(COMMIT_TWO))
                .body(not(containsString("Updated telemetry profile version")));

        String refreshedContext = enrich(ownerWorkspace, owner, "find my telemetry profile version");
        assertTrue(refreshedContext.contains("Updated telemetry profile version"));
        assertFalse(refreshedContext.contains(PUBLIC_SENTINEL));
        assertTrue(enrich(ownerWorkspace, owner, "architecture governance run guide")
                .contains("Source: docs/architecture.md (commit " + COMMIT_TWO + ")"));

        verify(auditService, times(2))
                .recordMandatory(
                        isNull(),
                        eq(owner),
                        eq("github-readme-connector"),
                        eq(SecurityEventType.EXTERNAL_SOURCE_INDEX_MUTATED),
                        eq("operation=refresh source=github-documentation"));
        ownerRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, otherWorkspace)
                .when()
                .get("/api/v1/connectors/github-readme/status")
                .then()
                .statusCode(403);
        verify(appClient, times(3)).fetchCurrent(owner, SETTINGS);
    }

    @Test
    void nonOwnerCannotReadRefreshOrDisconnectTheOwnersConnection() throws Exception {
        member = "workspace-member-" + UUID.randomUUID();
        createUser(member);
        createMembership(memberWorkspace, member, "MEMBER");
        SessionTokens tokens = createSession(member);
        memberSession = tokens.sessionToken();
        memberCsrf = tokens.csrfToken();

        when(appClient.fetchCurrent(owner, SETTINGS))
                .thenReturn(snapshot(COMMIT_ONE, PUBLIC_SENTINEL + " aircraft telemetry profile."));
        connectorService.connect(owner, memberWorkspace, SETTINGS);
        assertFalse(enrich(memberWorkspace, member, "find aircraft telemetry profile")
                .contains(PUBLIC_SENTINEL));
        clearInvocations(appClient);

        memberRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, memberWorkspace)
                .when()
                .get("/api/v1/connectors/github-readme/status")
                .then()
                .statusCode(403);
        memberRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, memberWorkspace)
                .when()
                .post("/api/v1/connectors/github-readme/refresh")
                .then()
                .statusCode(403);
        memberRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, memberWorkspace)
                .when()
                .delete("/api/v1/connectors/github-readme")
                .then()
                .statusCode(403);
        verify(appClient, never()).fetchCurrent(ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    @Test
    void nonInternalUserCannotConnect() {
        member = "ordinary-user-" + UUID.randomUUID();
        createUser(member);
        createMembership(ownerWorkspace, member, "MEMBER");
        SessionTokens tokens = createSession(member);
        memberSession = tokens.sessionToken();
        memberCsrf = tokens.csrfToken();

        memberRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, ownerWorkspace)
                .contentType("application/json")
                .body(SETTINGS_BODY)
                .when()
                .post("/api/v1/connectors/github-readme/connect")
                .then()
                .statusCode(403);
        verify(appClient, never()).fetchCurrent(ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    @Test
    void internalUiCanSelectAnOwnerWorkspaceAndConnectWithinThatScope() throws Exception {
        String internalApiKey = configuredApiKeys.get(AccessRoles.INTERNAL);
        assertTrue(internalApiKey != null && !internalApiKey.isBlank());
        when(appClient.fetchCurrent(owner, SETTINGS)).thenReturn(snapshot(COMMIT_ONE, PUBLIC_SENTINEL));

        given().header(HttpConstants.X_AMSHA_API_KEY, internalApiKey)
                .when()
                .get("/api/v1/connectors/github-readme/workspaces")
                .then()
                .statusCode(200)
                .body("workspaceId", hasItem(ownerWorkspace));

        given().header(HttpConstants.X_AMSHA_API_KEY, internalApiKey)
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, ownerWorkspace)
                .contentType("application/json")
                .body(SETTINGS_BODY)
                .when()
                .post("/api/v1/connectors/github-readme/connect")
                .then()
                .statusCode(200)
                .body("state", equalTo("INDEXED"));

        verify(appClient).fetchCurrent(owner, SETTINGS);
    }

    @Test
    void ownerCanListTheirActiveOwnerWorkspaces() {
        ownerRequest()
                .when()
                .get("/api/v1/connectors/github-readme/workspaces")
                .then()
                .statusCode(200)
                .body("workspaceId", hasItem(ownerWorkspace))
                .body("workspaceId", hasItem(memberWorkspace));
    }

    @Test
    void registeredOwnerApiKeyCanListTheirActiveOwnerWorkspaces() {
        String rawKey = "connector-owner-key-" + UUID.randomUUID();
        String credentialId = "connector-owner-credential-" + UUID.randomUUID();
        Instant now = Instant.now();
        assertTrue(apiCredentialStore.save(new ApiCredentialDto(
                credentialId,
                owner,
                apiKeyRegistry.computeDigest(rawKey),
                AccessRoles.OWNER,
                1,
                null,
                null,
                now,
                now)));
        apiKeyRegistry.register(owner, rawKey);
        try {
            given().header(HttpConstants.X_AMSHA_API_KEY, rawKey)
                    .when()
                    .get("/api/v1/connectors/github-readme/workspaces")
                    .then()
                    .statusCode(200)
                    .body("workspaceId", hasItem(ownerWorkspace));
        } finally {
            apiKeyRegistry.unregister(rawKey);
            apiCredentialStore.deleteById(credentialId);
        }
    }

    @Test
    void privateVisibilityChangeRemovesPreviouslyIndexedDocuments() throws Exception {
        GitHubReadmeSnapshot publicSnapshot =
                snapshot(COMMIT_ONE, "## About\n" + PUBLIC_SENTINEL + " architecture guide.");
        when(appClient.fetchCurrent(owner, SETTINGS))
                .thenReturn(publicSnapshot)
                .thenThrow(new GitHubReadmeSourceException(GitHubReadmeSourceException.Kind.SOURCE_NOT_PUBLIC));

        connectRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, ownerWorkspace)
                .when()
                .post("/api/v1/connectors/github-readme/connect")
                .then()
                .statusCode(200);

        ownerRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, ownerWorkspace)
                .when()
                .post("/api/v1/connectors/github-readme/refresh")
                .then()
                .statusCode(200)
                .body("state", equalTo("REMOVED"));

        assertFalse(enrich(ownerWorkspace, owner, "architecture guide").contains(PUBLIC_SENTINEL));
        verify(appClient, times(2)).fetchCurrent(owner, SETTINGS);
    }

    @Test
    void deletionRevocationDisconnectAndTransientFailureHaveDistinctEffects() throws Exception {
        GitHubReadmeSnapshot complete =
                snapshot(COMMIT_ONE, "## About\n" + PUBLIC_SENTINEL + " aircraft telemetry profile.");
        GitHubReadmeSnapshot afterDocumentDeletion = snapshotWithDocuments(
                COMMIT_TWO,
                List.of(new GitHubReadmeDocumentSnapshot(
                        "README.md",
                        "## About\n" + PUBLIC_SENTINEL + " aircraft telemetry profile.",
                        "3333333333333333333333333333333333333333")));
        when(appClient.fetchCurrent(owner, SETTINGS))
                .thenReturn(complete)
                .thenThrow(new GitHubReadmeSourceException(GitHubReadmeSourceException.Kind.TEMPORARY_FAILURE))
                .thenReturn(afterDocumentDeletion)
                .thenReturn(complete)
                .thenThrow(new GitHubReadmeSourceException(GitHubReadmeSourceException.Kind.ACCESS_REVOKED))
                .thenReturn(complete);

        connectRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, deleteWorkspace)
                .when()
                .post("/api/v1/connectors/github-readme/connect")
                .then()
                .statusCode(200);
        ownerRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, deleteWorkspace)
                .when()
                .post("/api/v1/connectors/github-readme/refresh")
                .then()
                .statusCode(503)
                .body(not(containsString(PUBLIC_SENTINEL)));
        assertTrue(enrich(deleteWorkspace, owner, "find aircraft telemetry profile")
                .contains(PUBLIC_SENTINEL));
        assertTrue(enrich(deleteWorkspace, owner, "architecture governance run guide")
                .contains("architecture-doc-only-sentinel"));

        ownerRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, deleteWorkspace)
                .when()
                .post("/api/v1/connectors/github-readme/refresh")
                .then()
                .statusCode(200)
                .body("state", equalTo("INDEXED"))
                .body("documents.size()", equalTo(1))
                .body("commitSha", equalTo(COMMIT_TWO));
        assertFalse(enrich(deleteWorkspace, owner, "architecture governance run guide")
                .contains("architecture-doc-only-sentinel"));

        connectRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, deleteWorkspace)
                .when()
                .post("/api/v1/connectors/github-readme/connect")
                .then()
                .statusCode(200);
        ownerRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, deleteWorkspace)
                .when()
                .post("/api/v1/connectors/github-readme/refresh")
                .then()
                .statusCode(200)
                .body("state", equalTo("REMOVED"));
        assertFalse(enrich(deleteWorkspace, owner, "find aircraft telemetry profile")
                .contains(PUBLIC_SENTINEL));

        connectRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, deleteWorkspace)
                .when()
                .post("/api/v1/connectors/github-readme/connect")
                .then()
                .statusCode(200);
        ownerRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, deleteWorkspace)
                .when()
                .delete("/api/v1/connectors/github-readme")
                .then()
                .statusCode(200)
                .body("state", equalTo("DISCONNECTED"));
        assertFalse(enrich(deleteWorkspace, owner, "find aircraft telemetry profile")
                .contains(PUBLIC_SENTINEL));
    }

    @Test
    void rejectsSecretBearingDocumentsWithoutIndexingOrReturningTheSecret() throws Exception {
        String secretText = "api" + "_key = " + "super" + "_secret_pass";
        when(appClient.fetchCurrent(owner, SETTINGS)).thenReturn(snapshot(COMMIT_ONE, secretText));

        String errorBody = connectRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, bodyWorkspace)
                .when()
                .post("/api/v1/connectors/github-readme/connect")
                .then()
                .statusCode(502)
                .extract()
                .asString();
        assertFalse(errorBody.contains(secretText));
        assertFalse(enrich(bodyWorkspace, owner, "api key secret").contains(secretText));
        ownerRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, bodyWorkspace)
                .when()
                .get("/api/v1/connectors/github-readme/status")
                .then()
                .statusCode(200)
                .body("state", equalTo("NOT_INDEXED"))
                .body(not(containsString(secretText)));
        verify(auditService, never())
                .recordMandatory(
                        ArgumentMatchers.isNull(),
                        eq(owner),
                        eq("github-readme-connector"),
                        eq(SecurityEventType.EXTERNAL_SOURCE_INDEX_MUTATED),
                        ArgumentMatchers.anyString());
    }

    @Test
    void rejectsBodiesAndRequestsWithoutAnActiveWorkspaceBeforeSourceAccess() {
        ownerRequest()
                .header(ActiveWorkspaceFilter.WORKSPACE_HEADER, bodyWorkspace)
                .contentType("application/json")
                .body("{\"repository\":\"attacker/other\"}")
                .when()
                .post("/api/v1/connectors/github-readme/connect")
                .then()
                .statusCode(400);
        ownerRequest()
                .contentType("application/json")
                .body(SETTINGS_BODY)
                .when()
                .post("/api/v1/connectors/github-readme/connect")
                .then()
                .statusCode(403);
        verify(appClient, never()).fetchCurrent(ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    private RequestSpecification ownerRequest() {
        return authenticatedRequest(ownerSession, ownerCsrf);
    }

    private RequestSpecification connectRequest() {
        return ownerRequest().contentType("application/json").body(SETTINGS_BODY);
    }

    private RequestSpecification memberRequest() {
        return authenticatedRequest(memberSession, memberCsrf);
    }

    private static RequestSpecification authenticatedRequest(String sessionToken, String csrfToken) {
        return given().cookie("AMSHA_SESSION_ID", sessionToken).header("X-CSRF-Token", csrfToken);
    }

    private void createUser(String userId) {
        Instant now = Instant.now();
        identityStore.saveUser(new IdentityUserDto(userId, userId, "ACTIVE", now, now));
    }

    private void createOwnerWorkspace(String workspaceId, String userId) {
        Instant now = Instant.now();
        identityStore.saveWorkspace(new IdentityWorkspaceDto(workspaceId, workspaceId, userId, now, now));
        createMembership(workspaceId, userId, "OWNER");
    }

    private void createMembership(String workspaceId, String userId, String role) {
        Instant now = Instant.now();
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

    private List<String> workspaceIds() {
        return List.of(ownerWorkspace, memberWorkspace, deleteWorkspace, bodyWorkspace);
    }

    private String enrich(String workspaceId, String userId, String goal) throws Exception {
        return WorkspaceContext.callWithActiveWorkspace(
                workspaceId, () -> contextEnricher.enrich("connector-session", userId, goal, "base prompt"));
    }

    private static GitHubReadmeSnapshot snapshot(String commit, String markdown) {
        return snapshotWithDocuments(
                commit,
                List.of(
                        new GitHubReadmeDocumentSnapshot("README.md", markdown, commit),
                        new GitHubReadmeDocumentSnapshot(
                                "docs/architecture.md",
                                "## Architecture\narchitecture-doc-only-sentinel architecture governance run guide.",
                                commit)));
    }

    private static GitHubReadmeSnapshot snapshotWithDocuments(
            String commit, List<GitHubReadmeDocumentSnapshot> documents) {
        return new GitHubReadmeSnapshot(42, "rsrini7/AmshaAgent", "main", commit, documents);
    }

    private record SessionTokens(String sessionToken, String csrfToken) {}
}
