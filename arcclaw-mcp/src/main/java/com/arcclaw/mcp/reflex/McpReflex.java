package com.arcclaw.mcp.reflex;

import com.arcclaw.core.reflex.AgentReflex;
import com.arcclaw.mcp.tool.DynamicToolCapabilityRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class McpReflex implements AgentReflex {

    @Inject DynamicToolCapabilityRegistry registry;

    @Override
    public boolean matches(String input) {
        return input != null && input.trim().equalsIgnoreCase("/mcp");
    }

    @Override
    public String execute(String sessionId, String userId, String chatId, String input) {
        int count = registry.getAll().size();
        return String.format("🤖 MCP Status: %d dynamic tools registered from connected servers.", count);
    }
}
