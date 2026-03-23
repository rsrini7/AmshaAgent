package com.arcclaw.mcp.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.reflex.AgentReflex;
import com.arcclaw.core.reflex.ReflexResult;
import com.arcclaw.mcp.bridge.McpToolBridge;
import com.arcclaw.mcp.tool.DynamicToolCapabilityRegistry;

@ApplicationScoped
public class McpReflex implements AgentReflex {

    @Inject DynamicToolCapabilityRegistry registry;
    @Inject McpToolBridge mcpToolBridge;

    @Override
    public boolean matches(String input) {
        return input != null && input.trim().equalsIgnoreCase("/mcp");
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        StringBuilder sb = new StringBuilder("🔌 *MCP Status*\n\n");
        sb.append("*Exposed internal tools* (").append(mcpToolBridge.getExposedTools().size()).append("):\n");
        mcpToolBridge.getExposedTools().forEach(t -> sb.append("• ").append(t.name()).append("\n"));
        if (registry.getAll().isEmpty()) {
            sb.append("\n*Dynamic MCP tools*: none connected\n");
        } else {
            sb.append("\n*Dynamic MCP tools* (").append(registry.getAll().size()).append("):\n");
            registry.getAll().forEach(t -> sb.append("• ")
                .append(t.toolName()).append(" [").append(t.mcpServerName())
                .append(", ").append(t.transport()).append("]\n"));
        }
        return ReflexResult.respond(sb.toString(), context);
    }
}
