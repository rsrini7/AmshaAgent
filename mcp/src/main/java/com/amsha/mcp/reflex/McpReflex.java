package com.amsha.mcp.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.reflex.AgentReflex;
import com.amsha.core.reflex.ReflexPriority;
import com.amsha.core.reflex.ReflexResult;
import com.amsha.mcp.bridge.McpToolBridge;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;

@ApplicationScoped
@ReflexPriority(500)
public class McpReflex implements AgentReflex {

    @Inject
    DynamicToolCapabilityRegistry registry;

    @Inject
    McpToolBridge mcpToolBridge;

    @Override
    public boolean matches(String input) {
        return input != null && input.trim().equalsIgnoreCase("/mcp");
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        StringBuilder sb = new StringBuilder("🔌 *MCP Status*\n\n");
        sb.append("*Exposed internal tools* (")
                .append(mcpToolBridge.getExposedTools().size())
                .append("):\n");
        mcpToolBridge
                .getExposedTools()
                .forEach(t -> sb.append("• ").append(t.name()).append("\n"));
        if (registry.getAll().isEmpty()) {
            sb.append("\n*Dynamic MCP tools*: none connected\n");
        } else {
            sb.append("\n*Dynamic MCP tools* (")
                    .append(registry.getAll().size())
                    .append("):\n");
            registry.getAll()
                    .forEach(t -> sb.append("• ")
                            .append(t.toolName())
                            .append(" [")
                            .append(t.mcpServerName())
                            .append(", ")
                            .append(t.transport())
                            .append("]\n"));
        }
        return ReflexResult.respond(sb.toString(), context);
    }
}
