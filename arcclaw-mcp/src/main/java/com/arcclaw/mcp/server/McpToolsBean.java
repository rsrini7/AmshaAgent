package com.arcclaw.mcp.server;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

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
        McpToolRequest req = new McpToolRequest("github", paramsJson, requestOpt.isResolvable() ? requestOpt.get() : null);
        return bridge.executeTool(req);
    }

    @Tool(name = "memory_search", description = "Search long-term episodic and semantic memory")
    public String memorySearch(String query) {
        String params = "{\"query\": \"" + query.replace("\"", "\\\"") + "\"}";
        McpToolRequest req = new McpToolRequest("memory_search", params, requestOpt.isResolvable() ? requestOpt.get() : null);
        return bridge.executeTool(req);
    }
}
