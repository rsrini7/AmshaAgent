package com.amsha.mcp.transport;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.AnnotationLiteral;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.http.ApprovalReceiptIssuer;
import com.amsha.core.security.http.OutboundApprovalReceipt;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.tool.api.ToolCategory;
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
        service.approvalReceiptIssuer = mock(ApprovalReceiptIssuer.class);
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
        assertEquals(wireMock.baseUrl(), service.httpServerUrl("test-server").orElse(null));
    }

    @Test
    void testClearHttpConnectionState() {
        stubFullConnect("session-abc");
        service.connect("test-server", wireMock.baseUrl());
        assertTrue(service.httpServerUrl("test-server").isPresent());

        service.clearHttpConnectionState("test-server");
        assertFalse(service.httpServerUrl("test-server").isPresent());

        service.connectForUserWithEnabledTools(
                "user-server", "usr-1", wireMock.baseUrl(), ToolCategory.NETWORK, Map.of(), false, Set.of());
        service.clearHttpConnectionState("user-server", "usr-1");
    }

    @Test
    void testConfigureHeaderSecretRefsUserMismatchAndEmpty() {
        assertThrows(
                McpConnectionException.class,
                () -> service.configureHeaderSecretRefs(
                        "server", "user-a", List.of(headerRef("r1", "c1", "user-b", "h1", "v1"))));

        assertDoesNotThrow(() -> service.configureHeaderSecretRefs("server", "user-a", List.of()));
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
    void testConnectForUserWithApprovalToken() throws Exception {
        stubFullConnect("session-approved");
        OutboundApprovalReceipt receipt =
                new OutboundApprovalReceipt("user-1", "sess-1", OutboundPurpose.MCP_CONNECT, "localhost");
        when(service.approvalReceiptIssuer.verifyAndConsume(
                        eq("tok-123"), eq(OutboundPurpose.MCP_CONNECT), anyString()))
                .thenReturn(receipt);

        WorkspaceContext.run("sess-1", "user-1", "chat-1", AutonomyLevel.FULL, "COORDINATOR", () -> {
            service.connectForUser(
                    "app-server", "user-1", wireMock.baseUrl(), ToolCategory.NETWORK, Map.of(), "tok-123");
            assertTrue(service.httpServerUrl("app-server").isPresent());
            return null;
        });
    }

    @Test
    void testConnectForUserWithInvalidApprovalTokenThrows() {
        when(service.approvalReceiptIssuer.verifyAndConsume(anyString(), any(), anyString()))
                .thenReturn(null);

        assertThrows(
                McpConnectionException.class,
                () -> service.connectForUser(
                        "app-server-bad", "user-1", wireMock.baseUrl(), ToolCategory.NETWORK, Map.of(), "bad-tok"));
    }

    @Test
    void testConnectToolsList500ThrowsMcpConnectionException() {
        wireMock.stubFor(post(urlEqualTo("/"))
                .withRequestBody(containing("\"initialize\""))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{"
                                + "\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                                + "\"serverInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}")));
        wireMock.stubFor(post(urlEqualTo("/"))
                .withRequestBody(containing("\"notifications/initialized\""))
                .willReturn(aResponse().withStatus(200).withBody("")));
        wireMock.stubFor(post(urlEqualTo("/"))
                .withRequestBody(containing("\"tools/list\""))
                .willReturn(aResponse().withStatus(500).withBody("Internal error")));

        assertThrows(McpConnectionException.class, () -> service.connect("fail-tools-server", wireMock.baseUrl()));
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
    void testExtractTextResultFallback() throws Exception {
        Method extract = McpClientService.class.getDeclaredMethod("extractTextResult", String.class);
        extract.setAccessible(true);

        String jsonWithoutContent = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":\"ok\"}}";
        String res = (String) extract.invoke(service, jsonWithoutContent);
        assertTrue(res.contains("status"));
    }

    @Test
    void testHealthCheckRunsNoThrow() throws Exception {
        Method healthCheck = McpClientService.class.getDeclaredMethod("healthCheck");
        healthCheck.setAccessible(true);
        assertDoesNotThrow(() -> healthCheck.invoke(service));
    }

    @Test
    void testCleanupNoThrow() {
        assertDoesNotThrow(service::cleanup);
    }

    private static McpConnectionHeaderSecretRefDto headerRef(
            String id, String connectionId, String userId, String headerName, String vaultKeyRef) {
        Instant now = Instant.now();
        return new McpConnectionHeaderSecretRefDto(id, connectionId, userId, headerName, vaultKeyRef, now, now);
    }
}
