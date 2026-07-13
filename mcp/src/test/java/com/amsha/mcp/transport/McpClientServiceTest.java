package com.amsha.mcp.transport;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.AnnotationLiteral;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.tools.ToolCategory;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class McpClientServiceTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private McpClientService service;
    private DynamicToolCapabilityRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new McpClientService();
        registry = new DynamicToolCapabilityRegistry();
        service.registry = registry;
        service.objectMapper = new ObjectMapper();
        service.meterRegistry = new SimpleMeterRegistry();
        service.credentialPort = mock(McpConnectionCredentialPort.class);
        when(service.credentialPort.resolveHeaders(any())).thenReturn(ResolvedMcpHeaders.empty());

        service.managedExecutors = mock(Instance.class);
        ManagedExecutor mockExec = ManagedExecutor.builder().build();
        when(service.managedExecutors.select(any(Class.class))).thenReturn(service.managedExecutors);
        when(service.managedExecutors.select(any(AnnotationLiteral.class))).thenReturn(service.managedExecutors);
        when(service.managedExecutors.get()).thenReturn(mockExec);
        when(service.managedExecutors.stream()).thenReturn(Stream.of(mockExec));

        service.init();
    }

    private void stubFullConnect(String sessionId) {
        wireMock.stubFor(post(urlEqualTo("/"))
                .withRequestBody(containing("\"initialize\""))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Mcp-Session-Id", sessionId)
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{"
                                + "\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                                + "\"serverInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}")));
        wireMock.stubFor(post(urlEqualTo("/"))
                .withRequestBody(containing("\"notifications/initialized\""))
                .willReturn(aResponse().withStatus(200).withBody("")));
        wireMock.stubFor(post(urlEqualTo("/"))
                .withRequestBody(containing("\"tools/list\""))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":["
                                + "{\"name\":\"external_search\","
                                + " \"description\":\"Search the web\","
                                + " \"inputSchema\":{\"type\":\"object\"}}]}}")));
    }

    @Test
    void testConnectRegistersToolsFromServer() {
        stubFullConnect("session-abc");
        service.connect("test-server", wireMock.baseUrl());

        assertTrue(registry.isRegistered("external_search"));
        var cap = registry.find("external_search").orElseThrow();
        assertEquals("test-server", cap.mcpServerName());
        assertEquals(McpTransportType.HTTP_SSE, cap.transport());
        assertEquals("Search the web", cap.description());
    }

    @Test
    void testConnectSendsDirectHandshakeHeaders() {
        wireMock.stubFor(post(urlEqualTo("/"))
                .withRequestBody(containing("\"initialize\""))
                .withHeader("X-API-Key", equalTo("secret"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Mcp-Session-Id", "session-with-header")
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{"
                                + "\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                                + "\"serverInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}")));
        wireMock.stubFor(post(urlEqualTo("/"))
                .withRequestBody(containing("\"notifications/initialized\""))
                .withHeader("X-API-Key", equalTo("secret"))
                .willReturn(aResponse().withStatus(200).withBody("")));
        wireMock.stubFor(post(urlEqualTo("/"))
                .withRequestBody(containing("\"tools/list\""))
                .withHeader("X-API-Key", equalTo("secret"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}")));

        service.connect("header-server", wireMock.baseUrl(), ToolCategory.NETWORK, Map.of("X-API-Key", "secret"));

        assertTrue(registry.getAll().isEmpty());
    }

    @Test
    void testConnectStoresSessionIdAndSendsItOnToolsCall() {
        stubFullConnect("my-session-id-xyz");
        wireMock.stubFor(post(urlEqualTo("/"))
                .withRequestBody(containing("\"tools/call\""))
                .withHeader("Mcp-Session-Id", equalTo("my-session-id-xyz"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":"
                                + "[{\"type\":\"text\",\"text\":\"search results here\"}]}}")));

        service.connect("test-server", wireMock.baseUrl());
        String result = service.invoke("test-server", "external_search", "{\"query\":\"hello\"}");
        assertEquals("search results here", result);
    }

    @Test
    void testInvokeThrowsWhenNoUrlRegistered() {
        assertThrows(McpConnectionException.class, () -> service.invoke("ghost-server", "some_tool", "{}"));
    }

    @Test
    void testConnectThrowsMcpConnectionExceptionOnServerError() {
        wireMock.stubFor(post(urlEqualTo("/"))
                .withRequestBody(containing("\"initialize\""))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        assertThrows(McpConnectionException.class, () -> service.connect("test-server", wireMock.baseUrl()));
    }

    @Test
    void testConnectWithEmptyToolsListRegistersNothing() {
        stubFullConnect("session-empty");
        wireMock.stubFor(post(urlEqualTo("/"))
                .withRequestBody(containing("\"tools/list\""))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}")));

        service.connect("empty-server", wireMock.baseUrl());
        assertTrue(registry.getAll().isEmpty());
    }

    @Test
    void testStoredHeadersAreResolvedOnlyForCurrentWorkspaceOwner() throws Exception {
        stubFullConnect("shared-session");
        wireMock.stubFor(post(urlEqualTo("/"))
                .withRequestBody(containing("\"tools/call\""))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":"
                                + "[{\"type\":\"text\",\"text\":\"owner result\"}]}}")));

        service.connectForUser("shared", "user-a", wireMock.baseUrl(), ToolCategory.NETWORK, Map.of());
        service.configureHeaderSecretRefs(
                "shared", "user-a", List.of(headerRef("ref-a", "conn-a", "user-a", "authorization", "vault-a")));
        service.connectForUser("shared", "user-b", wireMock.baseUrl(), ToolCategory.NETWORK, Map.of());
        service.configureHeaderSecretRefs(
                "shared", "user-b", List.of(headerRef("ref-b", "conn-b", "user-b", "authorization", "vault-b")));

        String result = WorkspaceContext.run(
                "session-a",
                "user-a",
                "chat-a",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> service.invoke("shared", "external_search", "{}"));

        assertEquals("owner result", result);
        verify(service.credentialPort).resolveHeaders("conn-a");
        verify(service.credentialPort, never()).resolveHeaders("conn-b");
    }

    private static McpConnectionHeaderSecretRefDto headerRef(
            String id, String connectionId, String userId, String headerName, String vaultKeyRef) {
        Instant now = Instant.now();
        return new McpConnectionHeaderSecretRefDto(id, connectionId, userId, headerName, vaultKeyRef, now, now);
    }
}
