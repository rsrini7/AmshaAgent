package com.arcclaw.mcp.tool;

import com.arcclaw.core.tool.DynamicToolLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class DynamicToolLookupImpl implements DynamicToolLookup {
    @Inject DynamicToolCapabilityRegistry registry;

    @Override
    public boolean isDynamicToolAllowed(String toolName) {
        // Registry presence IS the authorization for dynamic tools.
        // McpToolAllowlist is NOT consulted here — it governs outbound
        // exposure, not inbound invocation authorization.
        return registry.isRegistered(toolName);
    }
}
