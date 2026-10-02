package com.amsha.mcp.server;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Objects;

import com.amsha.mcp.bridge.McpToolBridge;
import com.amsha.mcp.bridge.McpToolRequest;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.mcp.server.Tool;
import io.vertx.core.http.HttpServerRequest;

@ApplicationScoped
public class McpToolsBean {
    @Inject
    McpToolBridge bridge;

    @Inject
    Instance<HttpServerRequest> requestOpt;

    @Inject
    ObjectMapper objectMapper;

    @Tool(name = "github", description = "Execute GitHub operations (search, pull, comment)")
    public String github(String operation, String paramsJson) {
        McpToolRequest req = new McpToolRequest(
                ToolCapability.GITHUB.toolName(), paramsJson, requestOpt.isResolvable() ? requestOpt.get() : null);
        return bridge.executeTool(req);
    }

    @Tool(name = "recall_memory_search", description = "Search exact conversation history (messages, code, errors)")
    public String recallMemorySearch(String query) {
        McpToolRequest req = new McpToolRequest(
                ToolCapability.RECALL_MEMORY_SEARCH.toolName(),
                queryPayload(query),
                requestOpt.isResolvable() ? requestOpt.get() : null);
        return bridge.executeTool(req);
    }

    @Tool(
            name = "archival_memory_search",
            description = "Semantic search across long-term knowledge (patterns, facts, preferences, project context)")
    public String archivalMemorySearch(String query) {
        McpToolRequest req = new McpToolRequest(
                ToolCapability.ARCHIVAL_MEMORY_SEARCH.toolName(),
                queryPayload(query),
                requestOpt.isResolvable() ? requestOpt.get() : null);
        return bridge.executeTool(req);
    }

    /**
     * Encodes the query as the {@code {"query": …}} payload the two search tools expect.
     *
     * <p>This builds the payload with Jackson instead of concatenating a hand-escaped string. The previous
     * implementation escaped only {@code "} and left {@code \} and control characters untouched, so a query
     * containing a Windows path, a trailing backslash, a tab, or a newline produced a payload that was not
     * valid JSON at all — and {@link McpToolBridge} hands that string straight to
     * {@code ToolRegistry.invokeTool} as JSON. {@code ObjectNode} serialisation cannot fail for a
     * {@code String} value, so there is no error branch to leave uncovered.
     *
     * <p>A null query fails fast rather than being encoded as JSON {@code null}: a missing required argument
     * is a malformed tool call, and the previous implementation rejected it too.
     */
    private String queryPayload(String query) {
        Objects.requireNonNull(query, "query");
        return objectMapper.createObjectNode().put("query", query).toString();
    }
}
