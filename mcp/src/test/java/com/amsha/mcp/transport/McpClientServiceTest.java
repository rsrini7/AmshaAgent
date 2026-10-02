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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.AnnotationLiteral;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.core.security.http.OutboundHttpException;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.ApprovalReceiptIssuer;
import com.amsha.security.http.OutboundApprovalReceipt;
import com.amsha.tool.api.ToolCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

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
                "user-server", "usr-1", wireMock.baseUrl(), ToolCategory.NETWORK, Map.of(), Set.of());
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
            service.connectForUserWithEnabledTools(
                    "app-server", "user-1", wireMock.baseUrl(), ToolCategory.NETWORK, Map.of(), "tok-123", Set.of());
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
                () -> service.connectForUserWithEnabledTools(
                        "app-server-bad",
                        "user-1",
                        wireMock.baseUrl(),
                        ToolCategory.NETWORK,
                        Map.of(),
                        "bad-tok",
                        Set.of()));
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
        String jsonWithoutContent = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":\"ok\"}}";
        String res = service.extractTextResult(jsonWithoutContent);
        assertTrue(res.contains("status"));
    }

    @Test
    void testHealthCheckRunsNoThrow() {
        assertDoesNotThrow(() -> service.healthCheck());
    }

    @Test
    void testCleanupNoThrow() {
        assertDoesNotThrow(service::cleanup);
    }

    @Test
    void testDiscoverForUserReturnsToolsWithoutRegistering() {
        stubFullConnect("session-disc");
        when(service.approvalReceiptIssuer.verifyAndConsume(eq("receipt-disc"), any(), any()))
                .thenReturn(new OutboundApprovalReceipt("user-d", "s1", OutboundPurpose.MCP_CONNECT, "127.0.0.1"));
        var tools = service.discoverForUser(
                "disc-server", "user-d", wireMock.baseUrl(), ToolCategory.NETWORK, Map.of(), "receipt-disc");
        assertNotNull(tools);
        assertEquals(1, tools.size());
        assertEquals("external_search", tools.get(0).name());
        // Read the owner's scope, not the ambient one: registration is scoped to the owning user, while the
        // scope-less accessor resolves to "unknown" outside a workspace. Asserting against the ambient scope would
        // pass no matter what discovery did.
        assertTrue(registry.find("user-d", "external_search").isEmpty());
    }

    @Test
    void testHasStdioServerReturnsFalseWhenNoProcess() {
        assertFalse(service.hasStdioServer("non-existent"));
    }

    @Test
    void testConnectForUserWithEnabledToolsFiltersTools() {
        stubFullConnect("session-filter");
        when(service.approvalReceiptIssuer.verifyAndConsume(eq("receipt-filter"), any(), any()))
                .thenReturn(new OutboundApprovalReceipt("user-f", "s2", OutboundPurpose.MCP_CONNECT, "127.0.0.1"));
        var tools = service.connectForUserWithEnabledTools(
                "filter-server",
                "user-f",
                wireMock.baseUrl(),
                ToolCategory.NETWORK,
                Map.of(),
                "receipt-filter",
                Set.of("non_matching_tool"));
        assertNotNull(tools);
        assertTrue(
                registry.find("user-f", "external_search").isEmpty(),
                "the tool was excluded by the enabled-tool selection, so it must be absent from the owner's scope");
    }

    @Test
    void testConfigureHeaderSecretRefsAndStateCleanup() {
        // Mismatched user throws McpConnectionException
        var mismatchedRef = headerRef("ref-1", "conn-1", "other-user", "Authorization", "secret-vault-ref");
        assertThrows(
                McpConnectionException.class,
                () -> service.configureHeaderSecretRefs("test-srv", "user-1", List.of(mismatchedRef)));

        // Matching user configures successfully
        var matchingRef = headerRef("ref-2", "conn-2", "user-1", "Authorization", "secret-vault-ref-2");
        assertDoesNotThrow(() -> service.configureHeaderSecretRefs("test-srv", "user-1", List.of(matchingRef)));

        // Empty refs cleans up previous headers
        assertDoesNotThrow(() -> service.configureHeaderSecretRefs("test-srv", "user-1", List.of()));

        // Clear HTTP connection state
        assertDoesNotThrow(() -> service.clearHttpConnectionState("test-srv"));
        assertDoesNotThrow(() -> service.clearHttpConnectionState("test-srv", "user-1"));
        assertFalse(service.httpServerUrl("test-srv").isPresent());
    }

    @Test
    void testRegistrationFailureRecordsConnectExceptionMetric() {
        stubFullConnect("metric-session");
        service.registry = mock(DynamicToolCapabilityRegistry.class);
        Mockito.doThrow(new RuntimeException("Registry failure"))
                .when(service.registry)
                .registerForUser(any(), any());

        assertThrows(McpConnectionException.class, () -> service.connect("metric-test-server", wireMock.baseUrl()));

        assertEquals(
                1.0,
                service.meterRegistry
                        .counter(
                                "amsha.mcp.tool.load.failure",
                                "server",
                                "metric-test-server",
                                "phase",
                                "connect_exception")
                        .count());
    }

    @Test
    void testOutboundPolicyDenialWrapsToMcpConnectionException() {
        OutboundHttpPolicyService policyService = mock(OutboundHttpPolicyService.class);
        when(policyService.evaluate(anyString(), any(), any(), any(), any(boolean.class)))
                .thenReturn(
                        OutboundPolicyDecision.deny("Test egress policy block", OutboundPurpose.MCP_CONNECT, "system"));
        service.outboundPolicyService = policyService;
        service.init();

        McpConnectionException ex = assertThrows(
                McpConnectionException.class,
                () -> service.connect("blocked-server", "https://blocked.example.com/mcp"));
        assertTrue(ex.getMessage().contains("rejected: POLICY_DENIED"));
        assertNotNull(ex.getCause());
        assertInstanceOf(OutboundHttpException.class, ex.getCause());
    }

    // ---- approval lifecycle --------------------------------------------------

    @Test
    void discoverForUserWithoutAReceiptReturnsToolsWithoutRegisteringThemOrConsultingTheIssuer() {
        stubFullConnect("session-unapproved");

        var tools = service.discoverForUser(
                "unapproved-server", "user-1", wireMock.baseUrl(), ToolCategory.NETWORK, Map.of());

        assertEquals(1, tools.size());
        assertEquals("external_search", tools.get(0).name());
        assertTrue(
                registry.find("user-1", "external_search").isEmpty(),
                "discovery is an unapproved handshake and must not register executable tools");
        verify(service.approvalReceiptIssuer, never()).verifyAndConsume(anyString(), any(), anyString());
    }

    @Test
    void connectForUserWithEnabledToolsTreatsABlankTokenAsUnapprovedAndStillConnects() {
        stubFullConnect("session-blank-token");

        var tools = service.connectForUserWithEnabledTools(
                "blank-token-server",
                "user-1",
                wireMock.baseUrl(),
                ToolCategory.NETWORK,
                Map.of(),
                "   ",
                Set.of("external_search"));

        assertEquals(1, tools.size());
        assertTrue(
                registry.find("user-1", "external_search").isPresent(),
                "a blank token is treated as absent, so the unapproved handshake still registers the enabled tool");
        verify(service.approvalReceiptIssuer, never()).verifyAndConsume(anyString(), any(), anyString());
    }

    @Test
    void connectForUserWithEnabledToolsStaysUnapprovedWhenNoReceiptIssuerIsWired() {
        service.approvalReceiptIssuer = null;
        stubFullConnect("session-no-issuer");

        var tools = service.connectForUserWithEnabledTools(
                "no-issuer-server",
                "user-1",
                wireMock.baseUrl(),
                ToolCategory.NETWORK,
                Map.of(),
                "some-token",
                Set.of());

        assertEquals(1, tools.size(), "a service without an issuer must degrade to the unapproved handshake");
        assertTrue(registry.find("user-1", "external_search").isEmpty());
    }

    @Test
    void connectForUserWithEnabledToolsWithAPurposeConsumesAReceiptScopedToThatPurpose() {
        stubFullConnect("session-purpose");
        when(service.approvalReceiptIssuer.verifyAndConsume(
                        eq("enable-tok"), eq(OutboundPurpose.MCP_TOOL_ENABLE), eq("localhost")))
                .thenReturn(
                        new OutboundApprovalReceipt("user-1", "sess-1", OutboundPurpose.MCP_TOOL_ENABLE, "localhost"));

        var tools = service.connectForUserWithEnabledTools(
                "purpose-server",
                "user-1",
                wireMock.baseUrl(),
                ToolCategory.NETWORK,
                Map.of(),
                "enable-tok",
                OutboundPurpose.MCP_TOOL_ENABLE,
                Set.of("external_search"));

        assertEquals(1, tools.size());
        assertTrue(
                registry.find("user-1", "external_search").isPresent(),
                "the receipt cleared MCP_TOOL_ENABLE, so the requested tool must be registered");
        verify(service.approvalReceiptIssuer)
                .verifyAndConsume("enable-tok", OutboundPurpose.MCP_TOOL_ENABLE, "localhost");
    }

    @Test
    void connectForUserWithEnabledToolsTreatsANullSelectionAsEmpty() {
        stubFullConnect("session-null-selection");

        var tools = service.connectForUserWithEnabledTools(
                "null-selection-server", "user-1", wireMock.baseUrl(), ToolCategory.NETWORK, Map.of(), (Set<String>)
                        null);

        assertEquals(1, tools.size(), "discovery still reports what the server offered");
        assertTrue(registry.find("user-1", "external_search").isEmpty(), "a null selection must register nothing");
    }

    @Test
    void reconnectDurableForUserRestoresTheConnectionWithoutConsultingTheIssuer() {
        stubFullConnect("session-durable");

        var tools = service.reconnectDurableForUser(
                "durable-server",
                "user-1",
                wireMock.baseUrl(),
                ToolCategory.NETWORK,
                Map.of(),
                Set.of("external_search"));

        assertEquals(1, tools.size());
        assertTrue(
                registry.find("user-1", "external_search").isPresent(),
                "a durable reconnect restores the connection's enabled tools");
        verify(service.approvalReceiptIssuer, never()).verifyAndConsume(anyString(), any(), anyString());
    }

    @Test
    void reconnectDurableForUserTreatsANullSelectionAsRegisteringNothing() {
        stubFullConnect("session-durable-null");

        var tools = service.reconnectDurableForUser(
                "durable-null-server", "user-1", wireMock.baseUrl(), ToolCategory.NETWORK, Map.of(), null);

        assertEquals(1, tools.size(), "discovery still reports what the server offered");
        assertTrue(registry.find("user-1", "external_search").isEmpty());
    }

    @Test
    void reconnectDurableForUserReplaysTheConnectionAsPreApproved() {
        // preApproved only reaches the wire through the governed client, so the policy service is the single place it
        // is observable. Denying there keeps this offline: the assertion needs no server at all.
        OutboundHttpPolicyService policyService = mock(OutboundHttpPolicyService.class);
        ArgumentCaptor<Boolean> preApproved = ArgumentCaptor.forClass(Boolean.class);
        when(policyService.evaluate(anyString(), any(), any(), anyString(), preApproved.capture()))
                .thenReturn(OutboundPolicyDecision.deny("blocked", OutboundPurpose.MCP_CONNECT, "system"));
        service.outboundPolicyService = policyService;
        service.init();

        assertThrows(
                McpConnectionException.class,
                () -> service.reconnectDurableForUser(
                        "durable-server",
                        "user-1",
                        "https://mcp.example.com/mcp",
                        ToolCategory.NETWORK,
                        Map.of(),
                        Set.of()));

        assertTrue(preApproved.getValue(), "a durable reconnect must be replayed with preApproved = true");
    }

    @Test
    void connectReplaysAsNotPreApproved() {
        OutboundHttpPolicyService policyService = mock(OutboundHttpPolicyService.class);
        ArgumentCaptor<Boolean> preApproved = ArgumentCaptor.forClass(Boolean.class);
        when(policyService.evaluate(anyString(), any(), any(), anyString(), preApproved.capture()))
                .thenReturn(OutboundPolicyDecision.deny("blocked", OutboundPurpose.MCP_CONNECT, "system"));
        service.outboundPolicyService = policyService;
        service.init();

        assertThrows(
                McpConnectionException.class, () -> service.connect("fresh-server", "https://mcp.example.com/mcp"));

        assertFalse(
                preApproved.getValue(),
                "an interactive connect has not cleared HITL and must not claim to be pre-approved");
    }

    @Test
    void verifyApprovalRejectsAReceiptIssuedForADifferentUser() {
        // A well-formed, in-date receipt for the right purpose is still not enough: it must have been issued to the
        // user who is asking, or one user's approval would authorise another user's connection.
        when(service.approvalReceiptIssuer.verifyAndConsume(eq("other-user-tok"), any(), anyString()))
                .thenReturn(new OutboundApprovalReceipt(
                        "someone-else", "sess-1", OutboundPurpose.MCP_CONNECT, "127.0.0.1"));

        McpConnectionException thrown = assertThrows(
                McpConnectionException.class,
                () -> service.connectForUserWithEnabledTools(
                        "cross-user-server",
                        "user-1",
                        wireMock.baseUrl(),
                        ToolCategory.NETWORK,
                        Map.of(),
                        "other-user-tok",
                        Set.of()));

        assertTrue(thrown.getMessage().contains("requires approval for requesting user"), thrown.getMessage());
    }

    @Test
    void connectForUserWithEnabledToolsWithAPurposeTreatsANullSelectionAsEmpty() {
        stubFullConnect("session-purpose-null");
        when(service.approvalReceiptIssuer.verifyAndConsume(
                        eq("enable-tok"), eq(OutboundPurpose.MCP_TOOL_ENABLE), anyString()))
                .thenReturn(
                        new OutboundApprovalReceipt("user-1", "sess-1", OutboundPurpose.MCP_TOOL_ENABLE, "localhost"));

        var tools = service.connectForUserWithEnabledTools(
                "purpose-null-server",
                "user-1",
                wireMock.baseUrl(),
                ToolCategory.NETWORK,
                Map.of(),
                "enable-tok",
                OutboundPurpose.MCP_TOOL_ENABLE,
                null);

        assertEquals(1, tools.size(), "discovery still reports what the server offered");
        assertTrue(
                registry.find("user-1", "external_search").isEmpty(),
                "a null selection must register nothing on the purpose-scoped path too");
    }

    @Test
    void invokeWrapsACheckedTransportFailureAndTagsTheMetricWithItsType() {
        stubFullConnect("session-malformed-call");
        wireMock.stubFor(post(urlEqualTo("/"))
                .withRequestBody(containing("\"tools/call\""))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{not valid json")));

        service.connect("malformed-server", wireMock.baseUrl());

        // extractTextResult declares a checked exception and the HTTP transport does not wrap it, so a malformed
        // 200 body arrives here as a checked failure rather than as a RuntimeException.
        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> service.invoke("malformed-server", "external_search", "{}"));

        assertTrue(thrown.getMessage().contains("Failed to invoke tool 'external_search'"), thrown.getMessage());
        assertNotNull(thrown.getCause());
        assertFalse(thrown.getCause() instanceof RuntimeException, "the cause must be the checked parse failure");
        assertEquals(
                1.0,
                service.meterRegistry
                        .get("amsha.mcp.tool.invocation")
                        .tag("server", "malformed-server")
                        .tag("tool", "external_search")
                        .tag("exception", thrown.getCause().getClass().getSimpleName())
                        .timer()
                        .count(),
                "the invocation timer must be tagged with the failure's type, not 'none'");
    }

    // ---- registerTools -------------------------------------------------------

    @Test
    void registerToolsReturnsNothingWhenTheResultCarriesNoToolsArray() {
        assertTrue(service.registerTools(
                        "srv", json("{}"), McpTransportType.HTTP_SSE, ToolCategory.NETWORK, "user-1", false, null)
                .isEmpty());
        assertTrue(service.registerTools(
                        "srv",
                        json("{\"tools\":\"not-an-array\"}"),
                        McpTransportType.HTTP_SSE,
                        ToolCategory.NETWORK,
                        "user-1",
                        false,
                        null)
                .isEmpty());
    }

    @Test
    void registerToolsSkipsToolsWithABlankName() {
        var discovered = service.registerTools(
                "srv",
                json("{\"tools\":[{\"name\":\"\"},{\"name\":\"   \"},{\"name\":\"real\"}]}"),
                McpTransportType.HTTP_SSE,
                ToolCategory.NETWORK,
                "user-1",
                false,
                null);

        assertEquals(
                List.of("real"),
                discovered.stream().map(McpClientService.DiscoveredTool::name).toList());
        assertEquals(
                1,
                registry.getAll("user-1").size(),
                "a nameless tool must not reach the registry, and the survivor lands in the owner's scope");
    }

    @Test
    void registerToolsDropsNamesThatCollideWithInternalToolsOnlyWhenAsked() {
        DynamicToolCapabilityRegistry mockedRegistry = mock(DynamicToolCapabilityRegistry.class);
        when(mockedRegistry.isInternalToolName("read_file")).thenReturn(true);
        service.registry = mockedRegistry;
        JsonNode tools = json("{\"tools\":[{\"name\":\"read_file\"}]}");

        var filtered =
                service.registerTools("srv", tools, McpTransportType.STDIO, ToolCategory.NETWORK, "user-1", true, null);

        assertTrue(
                filtered.isEmpty(),
                "an internal-name collision is dropped before the tool is reported, so nothing is even offered");
        verify(mockedRegistry, never()).registerForUser(anyString(), any());

        // The guard is opt-in: with it off the same name is reported and handed to the registry, which is what
        // makes the filtered case above a real assertion rather than a tautology.
        var unfiltered = service.registerTools(
                "srv", tools, McpTransportType.STDIO, ToolCategory.NETWORK, "user-1", false, null);

        assertEquals(
                List.of("read_file"),
                unfiltered.stream().map(McpClientService.DiscoveredTool::name).toList(),
                "without the guard the name is reported and handed to the registry");
        verify(mockedRegistry).registerForUser(eq("user-1"), any());
    }

    @Test
    void registerToolsDefaultsAMissingSchemaToAnEmptyObjectAndAMissingDescriptionToEmpty() {
        var discovered = service.registerTools(
                "srv",
                json("{\"tools\":["
                        + "{\"name\":\"with_schema\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}},"
                        + "{\"name\":\"without_schema\"}]}"),
                McpTransportType.HTTP_SSE,
                ToolCategory.NETWORK,
                "user-1",
                false,
                null);

        assertEquals("{\"type\":\"object\"}", discovered.get(0).inputSchema());
        assertEquals("d", discovered.get(0).description());
        assertEquals("{}", discovered.get(1).inputSchema());
        assertEquals("", discovered.get(1).description());
    }

    private JsonNode json(String raw) {
        try {
            return service.objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("test fixture is not valid JSON: " + raw, e);
        }
    }

    private static McpConnectionHeaderSecretRefDto headerRef(
            String id, String connectionId, String userId, String headerName, String vaultKeyRef) {
        Instant now = Instant.now();
        return new McpConnectionHeaderSecretRefDto(id, connectionId, userId, headerName, vaultKeyRef, now, now);
    }
}
