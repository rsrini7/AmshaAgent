package com.arcclaw.app.mcp;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import com.arcclaw.mcp.bridge.McpToolBridge;
import com.arcclaw.mcp.config.McpToolAllowlist;
import com.arcclaw.mcp.tool.DynamicToolCapabilityRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;

@ApplicationScoped
@Path("/mcp")
@RunOnVirtualThread
public class McpAdminResource {

    @Inject McpToolAllowlist config;
    @Inject McpToolBridge bridge;
    @Inject DynamicToolCapabilityRegistry registry;
    
    private void checkEnabled() {
        if (!config.serverEnabled()) {
            throw new NotFoundException();
        }
    }

    @GET
    @Path("/tools")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> listAllTools() {
        checkEnabled();
        return Map.of(
            "exposedInternalTools", bridge.getExposedTools(),
            "registeredDynamicTools", registry.getAll()
        );
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getStatus() {
        checkEnabled();
        return Map.of(
            "serverEnabled", config.serverEnabled(),
            "dynamicToolCount", registry.getAll().size()
        );
    }
}
