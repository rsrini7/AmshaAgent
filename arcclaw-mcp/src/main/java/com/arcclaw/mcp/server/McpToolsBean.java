package com.arcclaw.mcp.server;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.mcp.bridge.McpToolBridge;
import com.arcclaw.mcp.bridge.McpToolRequest;
import io.quarkiverse.mcp.server.Tool;
import io.vertx.core.http.HttpServerRequest;

@ApplicationScoped
public class McpToolsBean {
    @Inject McpToolBridge bridge;
    @Inject Instance<HttpServerRequest> requestOpt;

    @Tool(name = "github", description = "Execute GitHub operations (search, pull, comment)")
    public String github(String operation, String paramsJson) {
        McpToolRequest req = new McpToolRequest(ToolCapability.GITHUB.toolName(), paramsJson, requestOpt.isResolvable() ? requestOpt.get() : null);
        return bridge.executeTool(req);
    }

    @Tool(name = "recall_memory_search", description = "Search exact conversation history (messages, code, errors)")
    public String recallMemorySearch(String query) {
        String params = "{\"query\": \"" + query.replace("\"", "\\\"") + "\"}";
        McpToolRequest req = new McpToolRequest(ToolCapability.RECALL_MEMORY_SEARCH.toolName(), params, requestOpt.isResolvable() ? requestOpt.get() : null);
        return bridge.executeTool(req);
    }

    @Tool(name = "archival_memory_search", description = "Semantic search across long-term knowledge (patterns, facts, preferences, project context)")
    public String archivalMemorySearch(String query) {
        String params = "{\"query\": \"" + query.replace("\"", "\\\"") + "\"}";
        McpToolRequest req = new McpToolRequest(ToolCapability.ARCHIVAL_MEMORY_SEARCH.toolName(), params, requestOpt.isResolvable() ? requestOpt.get() : null);
        return bridge.executeTool(req);
    }
}
