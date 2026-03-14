package com.arcclaw.mcp.reflex;

import com.arcclaw.core.reflex.AgentReflex;
import com.arcclaw.mcp.tool.DynamicToolCapabilityRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class McpReflex implements AgentReflex {

    @Inject DynamicToolCapabilityRegistry registry;
    @Inject com.arcclaw.mcp.bridge.McpToolBridge mcpToolBridge;

    @Override
    public boolean matches(String input) {
        return input != null && input.trim().equalsIgnoreCase("/mcp");
    }

    @Override
    public String execute(String sessionId, String userId, String chatId, String input) {
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
        return sb.toString();
    }
}
