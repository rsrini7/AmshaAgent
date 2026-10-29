package com.amsha.app.connector.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

class DefaultGitHubReadmeAppClientTest {
    private static final String OWNER = "test-owner";
    private static final String TOKEN = "token-fixture-not-real";
    private static final String METADATA_TOKEN = "metadata-token-fixture";
    private static final String COMMIT_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String README_BLOB_SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String DOC_BLOB_SHA = "cccccccccccccccccccccccccccccccccccccccc";
    private static final String README_MARKDOWN = "# AmshaAgent\nEngineering agent documentation.";
    private static final String DOC_MARKDOWN = "# Architecture\nRun level agent orchestration.";
    private static final GitHubReadmeConnectionSettings SETTINGS = GitHubReadmeSourcePolicy.firstConnection();

    private GitHubReadmeTransport transport;
    private SecretVault secretVault;
    private DefaultGitHubReadmeAppClient client;
    private VaultKey privateKey;

    @BeforeEach
    void setUp() throws Exception {
        transport = mock(GitHubReadmeTransport.class);
        secretVault = mock(SecretVault.class);
        KeyPair keyPair = generateKeyPair();
        byte[] pem = ("-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getMimeEncoder(64, new byte[] {'\n'})
                                .encodeToString(keyPair.getPrivate().getEncoded())
                        + "\n-----END PRIVATE KEY-----\n")
                .getBytes(StandardCharsets.US_ASCII);
        privateKey = new VaultKey(pem.clone());
        when(secretVault.retrieveSystemKey(VaultConstants.GITHUB_APP_PRIVATE_KEY))
                .thenReturn(Optional.of(privateKey));
        client = new DefaultGitHubReadmeAppClient(
                transport, secretVault, new GitHubReadmePayloadParser(new ObjectMapper()), "12345");
    }

    @Test
    void readsOnlyAllowedMarkdownFromTheDefaultBranchCommitAndCorrelatesProvenance() throws Exception {
        stubSuccessfulFetch();

        GitHubReadmeSnapshot snapshot = client.fetchCurrent(OWNER, SETTINGS);

        assertEquals(42, snapshot.repositoryId());
        assertEquals("rsrini7/AmshaAgent", snapshot.repositoryFullName());
        assertEquals("main", snapshot.defaultBranch());
        assertEquals(COMMIT_SHA, snapshot.commitSha());
        assertEquals(
                List.of("README.md", "docs/architecture.md"),
                snapshot.documents().stream()
                        .map(GitHubReadmeDocumentSnapshot::path)
                        .sorted()
                        .toList());
        GitHubReadmeDocumentSnapshot readme = snapshot.documents().stream()
                .filter(document -> document.path().equals("README.md"))
                .findFirst()
                .orElseThrow();
        assertEquals(README_MARKDOWN, readme.markdown());
        assertEquals(README_BLOB_SHA, readme.blobSha());
        GitHubReadmeDocumentSnapshot architecture = snapshot.documents().stream()
                .filter(document -> document.path().equals("docs/architecture.md"))
                .findFirst()
                .orElseThrow();
        assertEquals(DOC_MARKDOWN, architecture.markdown());
        assertEquals(DOC_BLOB_SHA, architecture.blobSha());
        assertTrue(privateKey.isDestroyed());

        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(9)).send(requests.capture(), eq(OWNER), anyString());
        List<HttpRequest> captured = requests.getAllValues();
        assertTrue(captured.get(0).uri().toString().endsWith("/repos/rsrini7/AmshaAgent/installation"));
        assertTrue(captured.get(5).uri().toString().endsWith("/commits/main"));
        assertTrue(captured.get(6).uri().toString().endsWith("/git/trees/" + COMMIT_SHA + "?recursive=1"));
        List<String> contentPaths = captured.stream()
                .map(request -> request.uri().getRawPath())
                .filter(path -> path.contains("/contents/"))
                .toList();
        assertEquals(
                List.of(
                        "/repos/rsrini7/AmshaAgent/contents/README.md",
                        "/repos/rsrini7/AmshaAgent/contents/docs/architecture.md"),
                contentPaths);
        assertTrue(captured.get(3)
                .headers()
                .firstValue("Authorization")
                .orElseThrow()
                .startsWith("Bearer "));
        assertTrue(readBody(captured.get(3)).contains("\"contents\":\"read\""));
        assertFalse(readBody(captured.get(3)).contains("\"contents\":\"write\""));
    }

    @Test
    void rejectsUnselectedInstallationAndNeverMintsARepositoryToken() {
        when(transport.send(any(HttpRequest.class), eq(OWNER), anyString()))
                .thenReturn(json(
                        200,
                        "{\"id\":7,\"repository_selection\":\"all\"," + "\"permissions\":{\"contents\":\"read\"}}"));

        GitHubReadmeSourceException failure =
                assertThrows(GitHubReadmeSourceException.class, () -> client.fetchCurrent(OWNER, SETTINGS));

        assertEquals(GitHubReadmeSourceException.Kind.INVALID_RESPONSE, failure.kind());
        verify(transport, times(1)).send(any(HttpRequest.class), eq(OWNER), anyString());
        verify(transport, never()).send(any(HttpRequest.class), eq(OWNER), eq("create_repository_token"));
    }

    @Test
    void rejectsInstallationsThatIncludeAnotherRepositoryBeforeCreatingContentsToken() {
        when(transport.send(any(HttpRequest.class), eq(OWNER), anyString()))
                .thenReturn(
                        json(200, installationJson()),
                        json(201, "{\"token\":\"" + METADATA_TOKEN + "\",\"permissions\":{\"metadata\":\"read\"}}"),
                        json(
                                200,
                                "{\"total_count\":2,\"repositories\":["
                                        + "{\"id\":42,\"full_name\":\"rsrini7/AmshaAgent\"},"
                                        + "{\"id\":99,\"full_name\":\"other/repository\"}]}"));

        GitHubReadmeSourceException failure =
                assertThrows(GitHubReadmeSourceException.class, () -> client.fetchCurrent(OWNER, SETTINGS));

        assertEquals(GitHubReadmeSourceException.Kind.INVALID_RESPONSE, failure.kind());
        verify(transport, times(3)).send(any(HttpRequest.class), eq(OWNER), anyString());
        verify(transport, never()).send(any(HttpRequest.class), eq(OWNER), eq("create_repository_token"));
        verify(transport, never()).send(any(HttpRequest.class), eq(OWNER), eq("read_repository_metadata"));
    }

    @Test
    void rejectsTruncatedTreeBeforeFetchingOrReconcilingDocuments() {
        stubSuccessfulFetch();
        when(transport.send(any(HttpRequest.class), eq(OWNER), eq("read_default_branch_tree")))
                .thenReturn(json(200, "{\"truncated\":true,\"tree\":[]}"));

        GitHubReadmeSourceException failure =
                assertThrows(GitHubReadmeSourceException.class, () -> client.fetchCurrent(OWNER, SETTINGS));

        assertEquals(GitHubReadmeSourceException.Kind.INVALID_RESPONSE, failure.kind());
        verify(transport, never()).send(any(HttpRequest.class), eq(OWNER), eq("read_allowed_markdown"));
    }

    @Test
    void mapsRepositoryInstallationRevocationSeparately() {
        when(transport.send(any(HttpRequest.class), eq(OWNER), eq("get_installation")))
                .thenReturn(json(404, "{}"));

        GitHubReadmeSourceException failure =
                assertThrows(GitHubReadmeSourceException.class, () -> client.fetchCurrent(OWNER, SETTINGS));

        assertEquals(GitHubReadmeSourceException.Kind.ACCESS_REVOKED, failure.kind());
        verify(transport, times(1)).send(any(HttpRequest.class), eq(OWNER), eq("get_installation"));
    }

    @Test
    void rejectsSettingsOutsideTheFirstSourceBeforeReadingSecretsOrCallingGitHub() {
        GitHubReadmeConnectionSettings widened =
                new GitHubReadmeConnectionSettings("rsrini7/AmshaAgent", List.of("README.md", "docs/**"));

        GitHubReadmeSourceException failure =
                assertThrows(GitHubReadmeSourceException.class, () -> client.fetchCurrent(OWNER, widened));

        assertEquals(GitHubReadmeSourceException.Kind.INVALID_RESPONSE, failure.kind());
        verify(secretVault, never()).retrieveSystemKey(VaultConstants.GITHUB_APP_PRIVATE_KEY);
        verify(transport, never()).send(any(HttpRequest.class), anyString(), anyString());
    }

    @Test
    void missingCredentialsNeverEgress() {
        when(secretVault.retrieveSystemKey(VaultConstants.GITHUB_APP_PRIVATE_KEY))
                .thenReturn(Optional.empty());

        GitHubReadmeSourceException failure =
                assertThrows(GitHubReadmeSourceException.class, () -> client.fetchCurrent(OWNER, SETTINGS));

        assertEquals(GitHubReadmeSourceException.Kind.CREDENTIALS_MISSING, failure.kind());
        verify(transport, never()).send(any(HttpRequest.class), anyString(), anyString());
    }

    private void stubSuccessfulFetch() {
        when(transport.send(any(HttpRequest.class), eq(OWNER), anyString())).thenAnswer(this::responseFor);
    }

    private GitHubHttpResponse responseFor(InvocationOnMock invocation) {
        HttpRequest request = invocation.getArgument(0);
        String operation = invocation.getArgument(2);
        return switch (operation) {
            case "get_installation" -> json(200, installationJson());
            case "create_installation_metadata_token" ->
                json(201, "{\"token\":\"" + METADATA_TOKEN + "\",\"permissions\":{\"metadata\":\"read\"}}");
            case "list_installation_repositories" ->
                json(
                        200,
                        "{\"total_count\":1,\"repositories\":[" + "{\"id\":42,\"full_name\":\"rsrini7/AmshaAgent\"}]}");
            case "create_repository_token" ->
                json(
                        201,
                        "{\"token\":\"" + TOKEN + "\",\"permissions\":{\"contents\":\"read\"},"
                                + "\"repositories\":[{\"id\":42,\"full_name\":\"rsrini7/AmshaAgent\"}]}");
            case "read_repository_metadata" ->
                json(
                        200,
                        "{\"id\":42,\"full_name\":\"rsrini7/AmshaAgent\",\"private\":false,\"default_branch\":\"main\"}");
            case "read_default_branch_commit" -> json(200, "{\"sha\":\"" + COMMIT_SHA + "\"}");
            case "read_default_branch_tree" -> json(200, treeJson());
            case "read_allowed_markdown" ->
                request.uri().getRawPath().endsWith("/contents/README.md")
                        ? json(200, documentJson("README.md", README_MARKDOWN, README_BLOB_SHA))
                        : json(200, documentJson("docs/architecture.md", DOC_MARKDOWN, DOC_BLOB_SHA));
            default -> throw new AssertionError("Unexpected GitHub operation");
        };
    }

    private static String installationJson() {
        return "{\"id\":7,\"repository_selection\":\"selected\","
                + "\"permissions\":{\"contents\":\"read\",\"metadata\":\"read\"}}";
    }

    private static String treeJson() {
        return "{\"truncated\":false,\"tree\":["
                + treeEntry("README.md", "blob", "100644", README_BLOB_SHA, README_MARKDOWN.length()) + ","
                + treeEntry("docs/architecture.md", "blob", "100644", DOC_BLOB_SHA, DOC_MARKDOWN.length()) + ","
                + treeEntry("docs/generated/api.md", "blob", "100644", "dddddddddddddddddddddddddddddddddddddddd", 20)
                + ","
                + treeEntry("docs/link.md", "blob", "120000", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", 10) + ","
                + treeEntry("docs/submodule.md", "commit", "160000", "ffffffffffffffffffffffffffffffffffffffff", 0)
                + ","
                + treeEntry("docs/too-large.md", "blob", "100644", "abababababababababababababababababababab", 65537)
                + ","
                + treeEntry("src/Main.java", "blob", "100644", "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd", 100)
                + "]}";
    }

    private static String treeEntry(String path, String type, String mode, String sha, int size) {
        return "{\"path\":\"" + path + "\",\"type\":\"" + type + "\",\"mode\":\"" + mode + "\",\"sha\":\"" + sha
                + "\",\"size\":" + size + "}";
    }

    private static String documentJson(String path, String markdown, String blobSha) {
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        return "{\"path\":\"" + path + "\",\"type\":\"file\",\"encoding\":\"base64\",\"size\":"
                + bytes.length + ",\"sha\":\"" + blobSha + "\",\"content\":\""
                + Base64.getEncoder().encodeToString(bytes) + "\"}";
    }

    private static GitHubHttpResponse json(int status, String body) {
        return new GitHubHttpResponse(status, "application/vnd.github+json; charset=utf-8", "30", "", body);
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String readBody(HttpRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CountDownLatch completed = new CountDownLatch(1);
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer buffer) {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable failure) {
                completed.countDown();
            }

            @Override
            public void onComplete() {
                completed.countDown();
            }
        });
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        return output.toString(StandardCharsets.UTF_8);
    }
}
