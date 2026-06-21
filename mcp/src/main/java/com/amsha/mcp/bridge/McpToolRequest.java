package com.amsha.mcp.bridge;

import java.util.HashMap;
import java.util.Map;

import io.vertx.core.http.HttpServerRequest;

public class McpToolRequest {
    private final String toolName;
    private final String paramsJson;
    private final Map<String, String> headers = new HashMap<>();

    public McpToolRequest(String toolName, String paramsJson, HttpServerRequest req) {
        this.toolName = toolName;
        this.paramsJson = paramsJson;
        if (req != null) {
            req.headers().forEach(entry -> headers.put(entry.getKey(), entry.getValue()));
        }
    }

    public String toolName() {
        return toolName;
    }

    public String paramsJson() {
        return paramsJson;
    }

    public String getHeader(String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return "unknown";
    }
}
