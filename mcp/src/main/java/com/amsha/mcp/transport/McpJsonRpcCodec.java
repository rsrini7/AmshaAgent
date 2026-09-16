package com.amsha.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Shared MCP JSON-RPC message and result codec used by all transports.
 * Keeps protocol payload construction and result extraction consistent between HTTP and STDIO.
 */
final class McpJsonRpcCodec {

    private final ObjectMapper objectMapper;

    McpJsonRpcCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ObjectNode createInitParams() {
        ObjectNode initParams = objectMapper.createObjectNode();
        initParams.put("protocolVersion", "2024-11-05");
        initParams.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "amsha");
        clientInfo.put("version", "1.0");
        initParams.set("clientInfo", clientInfo);
        return initParams;
    }

    ObjectNode createCallParams(String toolName, String paramsJson) throws Exception {
        ObjectNode callParams = objectMapper.createObjectNode();
        callParams.put("name", toolName);
        callParams.set(
                "arguments",
                (paramsJson != null && !paramsJson.isBlank())
                        ? objectMapper.readTree(paramsJson)
                        : objectMapper.createObjectNode());
        return callParams;
    }

    String extractTextResult(String body) throws Exception {
        JsonNode result = objectMapper.readTree(body).path("result");
        JsonNode content = result.path("content");
        if (content.isArray() && !content.isEmpty()) {
            return content.get(0).path("text").asText();
        }
        return result.toString();
    }
}
