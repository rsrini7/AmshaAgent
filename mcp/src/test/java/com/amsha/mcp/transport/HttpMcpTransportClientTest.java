package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.security.api.SecretVault;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * URL/key resolution, credential wiring, and the governed-dispatch failure paths.
 *
 * <p>No CDI: the class is package-private with a plain six-arg constructor. Two paths deliberately use a real
 * {@link java.net.http.HttpClient} — {@code governedSend} builds one inline when no outbound policy service is
 * supplied — so those tests target a closed loopback port, which is refused immediately and deterministically
 * (no server, no DNS).
 */
@SuppressWarnings("java:S1192")
class HttpMcpTransportClientTest {

    /** Nothing listens on port 1, so a connect is refused at once. */
    private static final String UNREACHABLE = "http://127.0.0.1:1/mcp";

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpMcpTransportClient client(McpConnectionCredentialPort port, SimpleMeterRegistry registry) {
        return new HttpMcpTransportClient(
                objectMapper, registry, port, null, mock(SecretVault.class), new McpJsonRpcCodec(objectMapper));
    }

    private static McpConnectionHeaderSecretRefDto ref(String id, String userId, String headerName) {
        return new McpConnectionHeaderSecretRefDto(id, "conn-1", userId, headerName, "vault-" + id, T0, T0);
    }

    /**
     * Registers a URL in the client's map by driving a connect that is expected to fail on the closed loopback
     * port — {@code connect} stores the URL before it dispatches, so the state is in place even though the call
     * itself throws.
     */
    private static void registerUrl(HttpMcpTransportClient client, String runtimeKey, Map<String, String> headers) {
        assertThrows(
                McpConnectionException.class, () -> client.connect("srv", runtimeKey, UNREACHABLE, headers, false));
    }

    // ------------------------------------------------------------- identity keys

    @Test
    void isKnownUserRejectsNullBlankAndTheUnknownSentinel() {
        assertFalse(HttpMcpTransportClient.isKnownUser(null));
        assertFalse(HttpMcpTransportClient.isKnownUser(""));
        assertFalse(HttpMcpTransportClient.isKnownUser("   "));
        assertFalse(HttpMcpTransportClient.isKnownUser("unknown"), "the context fallback must not count as an owner");
        assertTrue(HttpMcpTransportClient.isKnownUser("user-1"));
    }

    @Test
    void runtimeKeyIsOwnerScopedOnlyForAKnownUser() {
        assertEquals("srv", HttpMcpTransportClient.runtimeKey("srv", null));
        assertEquals("srv", HttpMcpTransportClient.runtimeKey("srv", "unknown"));
        assertEquals("user-1@srv", HttpMcpTransportClient.runtimeKey("srv", "user-1"));
    }

    // ---------------------------------------------------------- self-connect guard

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost/mcp",
                "http://localhost:3000/mcp",
                "http://LOCALHOST:3000/mcp",
                "http://127.0.0.1:3000/mcp",
                "http://[::1]:3000/mcp",
                "http://localhost/mcp/"
            })
    void recognisesLoopbackMcpUrls(String url) {
        assertTrue(client(null, null).isSelfConnectUrl(url), () -> "must count as a self-connect: " + url);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://example.com/mcp",
                "http://localhost/sse",
                "http://localhost",
                "http://localhost:3000/mcp/v2",
                "not a url",
                "http://::1/mcp"
            })
    void rejectsEverythingElseAsNotASelfConnect(String url) {
        assertFalse(client(null, null).isSelfConnectUrl(url), () -> "must NOT count as a self-connect: " + url);
    }

    // ------------------------------------------------------- runtime key resolution

    @Test
    void resolveHttpRuntimeKeyFallsBackToTheBareNameForAnUnknownOwner() {
        assertEquals("srv", client(null, null).resolveHttpRuntimeKey("srv", "unknown"));
        assertEquals("srv", client(null, null).resolveHttpRuntimeKey("srv", null));
    }

    @Test
    void resolveHttpRuntimeKeyPrefersAUrlRegisteredUnderTheOwnerKey() throws Exception {
        HttpMcpTransportClient client = client(null, null);
        registerUrl(client, "user-1@srv", Map.of());

        assertEquals("user-1@srv", client.resolveHttpRuntimeKey("srv", "user-1"));
    }

    @Test
    void resolveHttpRuntimeKeyPrefersAnOwnerScopedCredentialRegistration() {
        McpConnectionCredentialPort port = mock(McpConnectionCredentialPort.class);
        HttpMcpTransportClient client = client(port, null);
        client.configureHeaderSecretRefs("srv", "user-1", List.of(ref("r1", "user-1", "Authorization")));

        assertEquals("user-1@srv", client.resolveHttpRuntimeKey("srv", "user-1"));
    }

    @Test
    void resolveHttpRuntimeKeyAdoptsABareNameRegistrationWhenNoOwnerScopeExists() throws Exception {
        HttpMcpTransportClient client = client(null, null);
        registerUrl(client, "srv", Map.of());

        assertEquals("srv", client.resolveHttpRuntimeKey("srv", "user-1"));
    }

    @Test
    void resolveHttpRuntimeKeyRefusesABareNameOnceAnotherOwnerHasScopedState() throws Exception {
        HttpMcpTransportClient client = client(null, null);
        registerUrl(client, "other@srv", Map.of());

        assertEquals(
                "user-1@srv",
                client.resolveHttpRuntimeKey("srv", "user-1"),
                "a pre-existing owner scope must not be silently shared via the bare name");
    }

    @Test
    void resolveHttpRuntimeKeyRefusesABareNameWhenAnotherOwnerHasCredentialsOnly() {
        McpConnectionCredentialPort port = mock(McpConnectionCredentialPort.class);
        HttpMcpTransportClient client = client(port, null);
        client.configureHeaderSecretRefs("srv", "user-2", List.of(ref("r1", "user-2", "Authorization")));

        assertEquals("user-1@srv", client.resolveHttpRuntimeKey("srv", "user-1"));
    }

    // ------------------------------------------------------------ serverUrl lookup

    @Test
    void serverUrlIsEmptyUntilAUrlIsRegistered() {
        HttpMcpTransportClient client = client(null, null);

        assertEquals(Optional.empty(), client.serverUrl("srv"));
        assertEquals(Optional.empty(), client.serverUrl("srv", "user-1"));
    }

    @Test
    void serverUrlResolvesThroughTheOwnerScopedKey() throws Exception {
        HttpMcpTransportClient client = client(null, null);
        registerUrl(client, "user-1@srv", Map.of());

        assertEquals(Optional.of(UNREACHABLE), client.serverUrl("srv", "user-1"));
    }

    @Test
    void serverUrlWithOneArgumentUsesTheContextOwner() throws Exception {
        HttpMcpTransportClient client = client(null, null);
        registerUrl(client, "srv", Map.of());

        assertEquals(Optional.of(UNREACHABLE), client.serverUrl("srv"));
    }

    // ------------------------------------------------------------ state clearing

    @Test
    void clearConnectionStateByBareNameDropsTheBareNameRegistration() throws Exception {
        HttpMcpTransportClient client = client(null, null);
        registerUrl(client, "srv", Map.of());

        client.clearConnectionState("srv");

        assertEquals(Optional.empty(), client.serverUrl("srv"));
    }

    @Test
    void clearConnectionStateByOwnerDropsTheOwnerScopedRegistrationAndItsCredentials() {
        McpConnectionCredentialPort port = mock(McpConnectionCredentialPort.class);
        HttpMcpTransportClient client = client(port, null);
        client.configureHeaderSecretRefs("srv", "user-1", List.of(ref("r1", "user-1", "Authorization")));

        client.clearConnectionState("srv", "user-1");

        verify(port).deleteHeaders("conn-1");
        assertEquals(Optional.empty(), client.serverUrl("srv", "user-1"));
    }

    @Test
    void clearConnectionStateDoesNotTouchThePortWhenNoCredentialsWereRegistered() throws Exception {
        McpConnectionCredentialPort port = mock(McpConnectionCredentialPort.class);
        HttpMcpTransportClient client = client(port, null);
        registerUrl(client, "srv", Map.of());

        client.clearConnectionState("srv");

        verifyNoInteractions(port);
    }

    // ------------------------------------------------------- credential wiring

    @Test
    void configureHeaderSecretRefsIsInertWithoutACredentialPort() {
        HttpMcpTransportClient client = client(null, null);

        client.configureHeaderSecretRefs("srv", "user-1", List.of(ref("r1", "user-1", "Authorization")));

        assertEquals(Optional.empty(), client.serverUrl("srv", "user-1"));
    }

    @Test
    void anEmptyRefListRemovesPreviouslyRegisteredCredentials() {
        McpConnectionCredentialPort port = mock(McpConnectionCredentialPort.class);
        HttpMcpTransportClient client = client(port, null);
        client.configureHeaderSecretRefs("srv", "user-1", List.of(ref("r1", "user-1", "Authorization")));

        client.configureHeaderSecretRefs("srv", "user-1", List.of());

        verify(port).deleteHeaders("conn-1");
    }

    @Test
    void aNullRefListRemovesPreviouslyRegisteredCredentials() {
        McpConnectionCredentialPort port = mock(McpConnectionCredentialPort.class);
        HttpMcpTransportClient client = client(port, null);
        client.configureHeaderSecretRefs("srv", "user-1", List.of(ref("r1", "user-1", "Authorization")));

        client.configureHeaderSecretRefs("srv", "user-1", null);

        verify(port).deleteHeaders("conn-1");
    }

    @Test
    void clearingCredentialsWithNothingRegisteredIsANoOp() {
        McpConnectionCredentialPort port = mock(McpConnectionCredentialPort.class);
        HttpMcpTransportClient client = client(port, null);

        client.configureHeaderSecretRefs("srv", "user-1", List.of());

        verify(port, never()).deleteHeaders(anyString());
    }

    @Test
    void configureHeaderSecretRefsRejectsARefOwnedByAnotherUser() {
        McpConnectionCredentialPort port = mock(McpConnectionCredentialPort.class);
        HttpMcpTransportClient client = client(port, null);

        McpConnectionException thrown = assertThrows(
                McpConnectionException.class,
                () -> client.configureHeaderSecretRefs("srv", "user-1", List.of(ref("r1", "user-2", "Authorization"))));

        assertTrue(thrown.getMessage().contains("owner mismatch"), thrown.getMessage());
        verify(port, never()).replaceHeaders(anyString(), anyString(), any());
    }

    @Test
    void configureHeaderSecretRefsRegistersTheConnectionAndForwardsEveryHeader() {
        McpConnectionCredentialPort port = mock(McpConnectionCredentialPort.class);
        HttpMcpTransportClient client = client(port, null);

        client.configureHeaderSecretRefs(
                "srv", "user-1", List.of(ref("r1", "user-1", "Authorization"), ref("r2", "user-1", "X-Trace")));

        verify(port).replaceHeaders(eq("conn-1"), eq("user-1"), any());
        assertEquals("user-1@srv", client.resolveHttpRuntimeKey("srv", "user-1"));
    }

    // --------------------------------------------------- pending-state migration

    @Test
    void registeringCredentialsMigratesABareNameUrlOntoTheOwnerKey() throws Exception {
        McpConnectionCredentialPort port = mock(McpConnectionCredentialPort.class);
        HttpMcpTransportClient client = client(port, null);
        registerUrl(client, "srv", Map.of());

        client.configureHeaderSecretRefs("srv", "user-1", List.of(ref("r1", "user-1", "Authorization")));

        assertEquals(
                Optional.of(UNREACHABLE),
                client.serverUrl("srv", "user-1"),
                "the pre-approval bare-name URL must follow the connection onto its owner key");
        assertEquals(Optional.empty(), client.serverUrl("srv"), "the bare-name entry must not be left behind");
    }

    @Test
    void migrationIsSkippedWhenTheOwnerKeyAlreadyEqualsTheServerName() {
        McpConnectionCredentialPort port = mock(McpConnectionCredentialPort.class);
        HttpMcpTransportClient client = client(port, null);

        client.configureHeaderSecretRefs("srv", "unknown", List.of(ref("r1", "unknown", "Authorization")));

        verify(port).replaceHeaders(eq("conn-1"), eq("unknown"), any());
        assertEquals("srv", client.resolveHttpRuntimeKey("srv", "unknown"));
    }

    // ----------------------------------------------------------- invoke / dispatch

    @Test
    void invokeRefusesAServerWithNoRegisteredUrl() {
        HttpMcpTransportClient client = client(null, null);

        McpConnectionException thrown =
                assertThrows(McpConnectionException.class, () -> client.invoke("srv", "tool-a", "{}", null));

        assertTrue(thrown.getMessage().contains("No URL registered"), thrown.getMessage());
    }

    @Test
    void invokeSurfacesATransportFailureAsAMcpConnectionException() throws Exception {
        HttpMcpTransportClient client = client(null, null);
        registerUrl(client, "srv", Map.of());

        assertThrows(McpConnectionException.class, () -> client.invoke("srv", "tool-a", "{\"x\":1}", null));
    }

    // ------------------------------------------------------ governed dispatch paths

    @Test
    void connectWithoutAPolicyServiceDispatchesDirectlyAndReportsTheTransportFailure() {
        HttpMcpTransportClient client = client(null, null);

        McpConnectionException thrown = assertThrows(
                McpConnectionException.class,
                () -> client.connect("srv", "srv", UNREACHABLE, Map.of("X-Handshake", "v"), false));

        assertTrue(thrown.getMessage().contains("HTTP MCP request failed"), thrown.getMessage());
    }

    @Test
    void aMalformedUrlIsRecordedAsAConnectExceptionMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HttpMcpTransportClient client = client(null, registry);

        McpConnectionException thrown = assertThrows(
                McpConnectionException.class, () -> client.connect("srv", "srv", "ht tp://host", Map.of(), false));

        assertTrue(thrown.getMessage().contains("Failed to connect to HTTP MCP server: srv"), thrown.getMessage());
        assertEquals(
                1.0,
                registry.counter("amsha.mcp.tool.load.failure", "server", "srv", "phase", "connect_exception")
                        .count(),
                "a connect failure must be counted once, tagged with the phase");
    }

    @Test
    void aConnectFailureIsNotDoubleCountedWhenTheTransportAlreadyWrappedIt() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HttpMcpTransportClient client = client(null, registry);

        assertThrows(McpConnectionException.class, () -> client.connect("srv", "srv", UNREACHABLE, Map.of(), false));

        assertEquals(
                0.0,
                registry.counter("amsha.mcp.tool.load.failure", "server", "srv", "phase", "connect_exception")
                        .count(),
                "governedSend already wrapped the failure, so connect must rethrow without re-recording");
    }

    @Test
    void aNullHandshakeHeaderMapIsTolerated() {
        HttpMcpTransportClient client = client(null, null);

        assertThrows(McpConnectionException.class, () -> client.connect("srv", "srv", UNREACHABLE, null, false));
    }

    @Test
    void recordFailureIsSkippedWhenNoMeterRegistryIsPresent() {
        HttpMcpTransportClient client = client(null, null);

        assertThrows(McpConnectionException.class, () -> client.connect("srv", "srv", "ht tp://host", Map.of(), false));
    }

    @Test
    void configuredHeadersAreResolvedFromThePortWhenNoDirectHeadersAreSupplied() throws Exception {
        McpConnectionCredentialPort port = mock(McpConnectionCredentialPort.class);
        when(port.resolveHeaders("conn-1")).thenReturn(ResolvedMcpHeaders.empty());
        HttpMcpTransportClient client = client(port, null);
        client.configureHeaderSecretRefs("srv", "user-1", List.of(ref("r1", "user-1", "Authorization")));
        // the handshake carries direct headers, so only the later tool call consults the credential port
        registerUrl(client, "user-1@srv", Map.of("X-Handshake", "v"));

        assertThrows(McpConnectionException.class, () -> client.invoke("srv", "tool-a", "{}", "user-1"));

        verify(port).resolveHeaders("conn-1");
    }

    @Test
    void invokeResolvesNoConfiguredHeadersWhenNothingWasRegisteredForTheKey() throws Exception {
        McpConnectionCredentialPort port = mock(McpConnectionCredentialPort.class);
        HttpMcpTransportClient client = client(port, null);
        registerUrl(client, "srv", Map.of());

        assertThrows(McpConnectionException.class, () -> client.invoke("srv", "tool-a", "{}", null));

        verify(port, never()).resolveHeaders(anyString());
    }

    @Test
    void extractTextResultIsDelegatedToTheSharedCodec() throws Exception {
        HttpMcpTransportClient client = client(null, null);

        String text = client.extractTextResult("{\"result\":{\"content\":[{\"text\":\"hello\"}]}}");

        assertEquals("hello", text);
    }
}
