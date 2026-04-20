package com.arcclaw.mcp.transport;

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
import static org.mockito.Mockito.when;

import java.util.stream.Stream;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.AnnotationLiteral;

import com.arcclaw.mcp.tool.DynamicToolCapabilityRegistry;
import com.arcclaw.mcp.tool.McpTransportType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

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
        service          = new McpClientService();
        registry         = new DynamicToolCapabilityRegistry();
        service.registry = registry;
        service.objectMapper = new ObjectMapper();
        
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
                .withBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{" +
                    "\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}," +
                    "\"serverInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}")));
        wireMock.stubFor(post(urlEqualTo("/"))
            .withRequestBody(containing("\"notifications/initialized\""))
            .willReturn(aResponse().withStatus(200).withBody("")));
        wireMock.stubFor(post(urlEqualTo("/"))
            .withRequestBody(containing("\"tools/list\""))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[" +
                    "{\"name\":\"external_search\"," +
                    " \"description\":\"Search the web\"," +
                    " \"inputSchema\":{\"type\":\"object\"}}]}}")));
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
    void testConnectStoresSessionIdAndSendsItOnToolsCall() {
        stubFullConnect("my-session-id-xyz");
        wireMock.stubFor(post(urlEqualTo("/"))
            .withRequestBody(containing("\"tools/call\""))
            .withHeader("Mcp-Session-Id", equalTo("my-session-id-xyz"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":" +
                    "[{\"type\":\"text\",\"text\":\"search results here\"}]}}")));

        service.connect("test-server", wireMock.baseUrl());
        String result = service.invoke("test-server", "external_search", "{\"query\":\"hello\"}");
        assertEquals("search results here", result);
    }

    @Test
    void testInvokeThrowsWhenNoUrlRegistered() {
        assertThrows(McpConnectionException.class,
            () -> service.invoke("ghost-server", "some_tool", "{}"));
    }

    @Test
    void testConnectThrowsMcpConnectionExceptionOnServerError() {
        wireMock.stubFor(post(urlEqualTo("/"))
            .withRequestBody(containing("\"initialize\""))
            .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        assertThrows(McpConnectionException.class,
            () -> service.connect("test-server", wireMock.baseUrl()));
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
}
