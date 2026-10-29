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

class DefaultGitHubReadmeAppClientTest {

    private static final String OWNER = "test-owner";
    private static final String TOKEN = "token-fixture-not-real";
    private static final String METADATA_TOKEN = "metadata-token-fixture";
    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private GitHubReadmeTransport transport;
    private SecretVault secretVault;
    private DefaultGitHubReadmeAppClient client;
    private VaultKey privateKey;

    @BeforeEach
    void setUp() throws Exception {
        transport = mock(GitHubReadmeTransport.class);
        secretVault = mock(SecretVault.class);
        byte[] pem = privateKeyPem();
        when(secretVault.retrieveSystemKey(VaultConstants.GITHUB_APP_PRIVATE_KEY))
                .thenAnswer(_ -> {
                    privateKey = new VaultKey(pem.clone());
                    return Optional.of(privateKey);
                });
        client = new DefaultGitHubReadmeAppClient(
                transport, secretVault, new GitHubReadmePayloadParser(new ObjectMapper()), "12345");
    }

    @Test
    void usesSelectedInstallationTokenAndCurrentDefaultBranchForOnlyRootReadme() throws Exception {
        stubSuccessfulFetch();

        Optional<GitHubReadmeSnapshot> snapshot = client.fetchCurrent(OWNER);

        assertTrue(snapshot.isPresent());
        assertEquals(42L, snapshot.orElseThrow().repositoryId());
        assertEquals("release/profile", snapshot.orElseThrow().defaultBranch());
        assertEquals(SHA, snapshot.orElseThrow().commitSha());
        assertEquals(
                "# Public profile\n\nSearchable fixture text.",
                snapshot.orElseThrow().markdown());
        assertTrue(privateKey.isDestroyed());

        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(7)).send(requests.capture(), eq(OWNER), anyString());
        List<HttpRequest> captured = requests.getAllValues();
        assertTrue(captured.get(0).uri().toString().endsWith("/repos/rsrini7/rsrini7/installation"));
        assertTrue(captured.get(0)
                .headers()
                .firstValue("Authorization")
                .orElseThrow()
                .startsWith("Bearer ey"));
        assertEquals("POST", captured.get(1).method());
        assertJsonEquals("{\"permissions\":{\"metadata\":\"read\"}}", readBody(captured.get(1)));
        assertEquals(
                "/installation/repositories?per_page=100&page=1",
                captured.get(2).uri().getRawPath() + "?" + captured.get(2).uri().getRawQuery());
        assertEquals(
                "Bearer " + METADATA_TOKEN,
                captured.get(2).headers().firstValue("Authorization").orElseThrow());
        assertEquals("POST", captured.get(3).method());
        assertJsonEquals(
                "{\"repositories\":[\"rsrini7\"],\"permissions\":{\"contents\":\"read\"}}", readBody(captured.get(3)));
        assertEquals(
                "Bearer " + TOKEN,
                captured.get(4).headers().firstValue("Authorization").orElseThrow());
        assertTrue(captured.get(5).uri().toString().contains("/commits/release%2Fprofile"));
        assertTrue(captured.get(6).uri().toString().endsWith("/contents/README.md?ref=" + SHA));
        assertFalse(captured.get(6).uri().toString().contains("/contents/docs/"));
    }

    @Test
    void removesContentWhenRootReadmeIsMissingAndClassifiesRateLimitAsTemporary() {
        stubSuccessfulFetch();
        when(transport.send(any(HttpRequest.class), eq(OWNER), eq("read_root_readme")))
                .thenReturn(json(404, "{}"));

        assertTrue(client.fetchCurrent(OWNER).isEmpty());

        stubSuccessfulFetch();
        when(transport.send(any(HttpRequest.class), eq(OWNER), eq("read_repository_metadata")))
                .thenReturn(new GitHubHttpResponse(403, "application/json", "30", "0", "[redacted]"));
        GitHubReadmeSourceException failure =
                assertThrows(GitHubReadmeSourceException.class, () -> client.fetchCurrent(OWNER));
        assertEquals(GitHubReadmeSourceException.Kind.TEMPORARY_FAILURE, failure.kind());
        assertFalse(failure.getMessage().contains("redacted"));
    }

    @Test
    void rejectsUnselectedInstallationBeforeRequestingAToken() {
        when(transport.send(any(HttpRequest.class), eq(OWNER), eq("get_installation")))
                .thenReturn(json(200, "{\"id\":7,\"repository_selection\":\"all\"}"));
        GitHubReadmeSourceException installationFailure =
                assertThrows(GitHubReadmeSourceException.class, () -> client.fetchCurrent(OWNER));
        assertEquals(GitHubReadmeSourceException.Kind.INVALID_RESPONSE, installationFailure.kind());
        verify(transport, times(1)).send(any(HttpRequest.class), eq(OWNER), eq("get_installation"));
        verify(transport, never()).send(any(HttpRequest.class), eq(OWNER), eq("create_repository_token"));
    }

    @Test
    void rejectsInstallationWritePermissionsBeforeRequestingAnyToken() {
        when(transport.send(any(HttpRequest.class), eq(OWNER), eq("get_installation")))
                .thenReturn(
                        json(
                                200,
                                "{\"id\":7,\"repository_selection\":\"selected\",\"permissions\":{\"contents\":\"write\",\"metadata\":\"read\"}}"));

        GitHubReadmeSourceException failure =
                assertThrows(GitHubReadmeSourceException.class, () -> client.fetchCurrent(OWNER));

        assertEquals(GitHubReadmeSourceException.Kind.INVALID_RESPONSE, failure.kind());
        verify(transport, times(1)).send(any(HttpRequest.class), eq(OWNER), eq("get_installation"));
        verify(transport, never()).send(any(HttpRequest.class), eq(OWNER), eq("create_installation_metadata_token"));
    }

    @Test
    void rejectsAnInstallationContainingAnotherRepositoryBeforeCreatingAContentsToken() {
        when(transport.send(any(HttpRequest.class), eq(OWNER), anyString()))
                .thenReturn(
                        json(
                                200,
                                "{\"id\":7,\"repository_selection\":\"selected\",\"permissions\":{\"contents\":\"read\",\"metadata\":\"read\"}}"),
                        json(201, "{\"token\":\"" + METADATA_TOKEN + "\",\"permissions\":{\"metadata\":\"read\"}}"),
                        json(
                                200,
                                "{\"total_count\":2,\"repositories\":["
                                        + "{\"id\":42,\"full_name\":\"rsrini7/rsrini7\"},"
                                        + "{\"id\":99,\"full_name\":\"other/repository\"}]}"));

        GitHubReadmeSourceException failure =
                assertThrows(GitHubReadmeSourceException.class, () -> client.fetchCurrent(OWNER));

        assertEquals(GitHubReadmeSourceException.Kind.INVALID_RESPONSE, failure.kind());
        verify(transport, times(3)).send(any(HttpRequest.class), eq(OWNER), anyString());
        verify(transport, never()).send(any(HttpRequest.class), eq(OWNER), eq("create_repository_token"));
        verify(transport, never()).send(any(HttpRequest.class), eq(OWNER), eq("read_repository_metadata"));
    }

    @Test
    void rejectsInvalidTokenPermissionBeforeReadingRepository() {
        stubSuccessfulFetch();
        when(transport.send(any(HttpRequest.class), eq(OWNER), eq("create_repository_token")))
                .thenReturn(
                        json(
                                200,
                                "{\"token\":\"" + TOKEN
                                        + "\",\"permissions\":{\"contents\":\"write\"},\"repositories\":[{\"id\":42,\"full_name\":\"rsrini7/rsrini7\"}]}"));
        GitHubReadmeSourceException tokenFailure =
                assertThrows(GitHubReadmeSourceException.class, () -> client.fetchCurrent(OWNER));
        assertEquals(GitHubReadmeSourceException.Kind.INVALID_RESPONSE, tokenFailure.kind());
        verify(transport, never()).send(any(HttpRequest.class), eq(OWNER), eq("read_repository_metadata"));
    }

    @Test
    void missingCredentialsNeverEgress() {
        when(secretVault.retrieveSystemKey(VaultConstants.GITHUB_APP_PRIVATE_KEY))
                .thenReturn(Optional.empty());

        GitHubReadmeSourceException failure =
                assertThrows(GitHubReadmeSourceException.class, () -> client.fetchCurrent(OWNER));

        assertEquals(GitHubReadmeSourceException.Kind.CREDENTIALS_MISSING, failure.kind());
        verify(transport, times(0)).send(any(HttpRequest.class), anyString(), anyString());
    }

    @Test
    void missingAppIdNeverReadsCredentialOrEgress() {
        DefaultGitHubReadmeAppClient unconfigured = new DefaultGitHubReadmeAppClient(
                transport, secretVault, new GitHubReadmePayloadParser(new ObjectMapper()), "none");

        GitHubReadmeSourceException failure =
                assertThrows(GitHubReadmeSourceException.class, () -> unconfigured.fetchCurrent(OWNER));

        assertEquals(GitHubReadmeSourceException.Kind.CREDENTIALS_MISSING, failure.kind());
        verify(secretVault, never()).retrieveSystemKey(VaultConstants.GITHUB_APP_PRIVATE_KEY);
        verify(transport, times(0)).send(any(HttpRequest.class), anyString(), anyString());
    }

    private void stubSuccessfulFetch() {
        when(transport.send(any(HttpRequest.class), eq(OWNER), anyString()))
                .thenReturn(
                        json(
                                200,
                                "{\"id\":7,\"repository_selection\":\"selected\",\"permissions\":{\"contents\":\"read\",\"metadata\":\"read\"}}"),
                        json(201, "{\"token\":\"" + METADATA_TOKEN + "\",\"permissions\":{\"metadata\":\"read\"}}"),
                        json(
                                200,
                                "{\"total_count\":1,\"repositories\":["
                                        + "{\"id\":42,\"full_name\":\"rsrini7/rsrini7\"}]}"),
                        json(
                                201,
                                "{\"token\":\"" + TOKEN
                                        + "\",\"permissions\":{\"contents\":\"read\",\"metadata\":\"read\"},"
                                        + "\"repositories\":[{\"id\":42,\"full_name\":\"rsrini7/rsrini7\"}]}"),
                        json(
                                200,
                                "{\"id\":42,\"full_name\":\"rsrini7/rsrini7\",\"private\":false,\"default_branch\":\"release/profile\"}"),
                        json(200, "{\"sha\":\"" + SHA + "\"}"),
                        readmeResponse("# Public profile\n\nSearchable fixture text."));
    }

    private static GitHubHttpResponse readmeResponse(String markdown) {
        String content = Base64.getEncoder().encodeToString(markdown.getBytes(StandardCharsets.UTF_8));
        String json = "{\"path\":\"README.md\",\"type\":\"file\",\"encoding\":\"base64\",\"size\":"
                + markdown.getBytes(StandardCharsets.UTF_8).length + ",\"sha\":\"" + SHA + "\",\"content\":\"" + content
                + "\"}";
        return json(200, json);
    }

    private static GitHubHttpResponse json(int status, String body) {
        return new GitHubHttpResponse(status, "application/vnd.github+json; charset=utf-8", "", "", body);
    }

    private static byte[] privateKeyPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(keyPair.getPrivate().getEncoded());
        return ("-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static String readBody(HttpRequest request) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CountDownLatch complete = new CountDownLatch(1);
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] copy = new byte[item.remaining()];
                item.get(copy);
                bytes.writeBytes(copy);
            }

            @Override
            public void onError(Throwable throwable) {
                complete.countDown();
            }

            @Override
            public void onComplete() {
                complete.countDown();
            }
        });
        assertTrue(complete.await(1, TimeUnit.SECONDS));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void assertJsonEquals(String expected, String actual) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(mapper.readTree(expected), mapper.readTree(actual));
    }
}
