package com.amsha.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import com.amsha.mcp.bridge.McpToolBridge;
import com.amsha.mcp.bridge.McpToolRequest;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;

/**
 * Tests for {@link McpToolsBean} — the thin {@code @Tool} adapter that turns MCP tool calls into
 * {@link McpToolRequest}s for {@link McpToolBridge}.
 *
 * <p>The class was entirely uncovered, which hid a real defect: the two memory-search methods build their
 * parameter payload by string concatenation, escaping only {@code "} and leaving {@code \} and control
 * characters alone. The payload is handed to {@code ToolRegistry.invokeTool} as JSON, so a query containing a
 * Windows path, a trailing backslash, a tab, or a newline produced a payload that <em>is not JSON at all</em>.
 * The round-trip assertions below are the point of this class: they parse the captured payload with a real
 * {@link ObjectMapper}, so an escaping mistake fails the test instead of reaching a tool.
 *
 * <p>{@code McpToolsBean} is a plain {@code @ApplicationScoped} bean with package-private {@code @Inject}
 * fields, so a test in this package wires the collaborators directly and no CDI container is needed.
 */
class McpToolsBeanTest {

    private McpToolsBean bean;
    private McpToolBridge bridge;
    private Instance<HttpServerRequest> requestOpt;

    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        bean = new McpToolsBean();
        bridge = mock(McpToolBridge.class);
        requestOpt = mock(Instance.class);
        bean.bridge = bridge;
        bean.requestOpt = requestOpt;
        bean.objectMapper = mapper;
    }

    private McpToolRequest capturedRequest() {
        ArgumentCaptor<McpToolRequest> captor = ArgumentCaptor.forClass(McpToolRequest.class);
        verify(bridge).executeTool(captor.capture());
        return captor.getValue();
    }

    /**
     * Parses the payload and asserts the query survived intact. A payload that is not valid JSON makes
     * {@code readTree} throw, so this fails loudly rather than asserting on a malformed string.
     */
    private void assertQueryRoundTrips(String paramsJson, String expectedQuery) throws Exception {
        JsonNode parsed = mapper.readTree(paramsJson);
        assertEquals(expectedQuery, parsed.path("query").asText(), () -> "payload was: " + paramsJson);
    }

    // ---------------------------------------------------------------- github

    @Test
    void githubForwardsTheCapabilityNameAndParamsVerbatim() {
        when(requestOpt.isResolvable()).thenReturn(false);
        when(bridge.executeTool(any())).thenReturn("bridge-output");

        String result = bean.github("search", "{\"q\":\"repos\"}");

        assertEquals("bridge-output", result, "the bridge's return value must be passed straight through");
        McpToolRequest request = capturedRequest();
        assertEquals(ToolCapability.GITHUB.toolName(), request.toolName());
        assertEquals("{\"q\":\"repos\"}", request.paramsJson(), "github must not rewrite the caller's params");
    }

    // ---------------------------------------------------------------- memory searches

    @Test
    void recallMemorySearchUsesItsCapabilityNameAndEncodesTheQuery() throws Exception {
        when(requestOpt.isResolvable()).thenReturn(false);

        bean.recallMemorySearch("hello world");

        McpToolRequest request = capturedRequest();
        assertEquals(ToolCapability.RECALL_MEMORY_SEARCH.toolName(), request.toolName());
        assertQueryRoundTrips(request.paramsJson(), "hello world");
    }

    @Test
    void archivalMemorySearchUsesItsCapabilityNameAndEncodesTheQuery() throws Exception {
        when(requestOpt.isResolvable()).thenReturn(false);

        bean.archivalMemorySearch("pattern \"x\"");

        McpToolRequest request = capturedRequest();
        assertEquals(ToolCapability.ARCHIVAL_MEMORY_SEARCH.toolName(), request.toolName());
        assertQueryRoundTrips(request.paramsJson(), "pattern \"x\"");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "he said \"hi\"", // a quote: the only case the original escaping handled
                "C:\\Users\\me", // a Windows path: every backslash is an invalid JSON escape
                "trailing\\", // a trailing backslash: terminates the string early
                "line1\nline2", // a real newline: an illegal control character inside a JSON string
                "a\tb", // a real tab: likewise
                "quote\"then\\slash" // both, adjacent
            })
    void recallMemorySearchProducesAJsonPayloadForEveryMetacharacter(String query) throws Exception {
        when(requestOpt.isResolvable()).thenReturn(false);

        bean.recallMemorySearch(query);

        assertQueryRoundTrips(capturedRequest().paramsJson(), query);
    }

    @ParameterizedTest
    @ValueSource(strings = {"C:\\Users\\me", "trailing\\", "line1\nline2", "a\tb"})
    void archivalMemorySearchProducesAJsonPayloadForEveryMetacharacter(String query) throws Exception {
        when(requestOpt.isResolvable()).thenReturn(false);

        bean.archivalMemorySearch(query);

        assertQueryRoundTrips(capturedRequest().paramsJson(), query);
    }

    @Test
    void aNullQueryFailsFastRatherThanBeingEncoded() {
        // A missing required argument is a malformed tool call; it must not become a search for JSON null.
        assertThrows(NullPointerException.class, () -> bean.recallMemorySearch(null));
    }

    // ---------------------------------------------------------------- HTTP request threading

    @Test
    void forwardsTheHttpRequestWhenTheInstanceIsResolvable() {
        HttpServerRequest httpRequest = mock(HttpServerRequest.class);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("X-MCP-Client-ID", "client-7");
        when(httpRequest.headers()).thenReturn(headers);
        when(requestOpt.isResolvable()).thenReturn(true);
        when(requestOpt.get()).thenReturn(httpRequest);

        // All three tools share the same `isResolvable() ? get() : null` expression, so each needs its own call
        // for the resolvable side to be exercised on every method.
        bean.github("search", "{}");
        bean.recallMemorySearch("anything");
        bean.archivalMemorySearch("anything");

        ArgumentCaptor<McpToolRequest> captor = ArgumentCaptor.forClass(McpToolRequest.class);
        verify(bridge, times(3)).executeTool(captor.capture());
        for (McpToolRequest request : captor.getAllValues()) {
            assertEquals("client-7", request.getHeader("X-MCP-Client-ID"));
        }
    }

    @Test
    void forwardsNoHttpRequestWhenTheInstanceIsNotResolvable() {
        when(requestOpt.isResolvable()).thenReturn(false);

        bean.archivalMemorySearch("anything");

        // McpToolRequest.getHeader falls back to this sentinel when nothing was threaded through.
        assertEquals("unknown", capturedRequest().getHeader("X-MCP-Client-ID"));
    }
}
