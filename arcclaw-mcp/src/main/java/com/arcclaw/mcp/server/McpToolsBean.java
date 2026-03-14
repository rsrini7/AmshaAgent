package com.arcclaw.mcp.server;

import com.arcclaw.core.audit.AuditEvent;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.persistence.AsyncPersistenceEngine;
import com.arcclaw.core.persistence.PersistenceEvent;
import com.arcclaw.mcp.bridge.McpToolBridge;

import io.quarkiverse.mcp.server.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class McpToolsBean {
    // private static final Logger log = Logger.getLogger(McpToolsBean.class);

    @Inject McpToolBridge bridge;
    @Inject AsyncPersistenceEngine persistenceEngine;

    @Tool(name = "github", description = "Execute GitHub operations (search, pull, comment)")
    public String github(String operation, String paramsJson) {
        audit("github", operation + " " + paramsJson);
        return bridge.invoke("github", paramsJson); // Bridge handles allowlist check
    }

    @Tool(name = "memory_search", description = "Search long-term episodic and semantic memory")
    public String memorySearch(String query) {
        audit("memory_search", query);
        // build json params for registry
        String params = "{\"query\":\"" + query.replace("\"", "\\\"") + "\"}";
        return bridge.invoke("memory_search", params);
    }

    private void audit(String toolName, String detail) {
        // MCP calls don't have a session ID in the same way, but we might have one in MDC if 
        // the request carries an ArcClaw header? The spec doesn't require it, so we use "mcp-external".
        String sid = "mcp-external";
        
        AuditEvent event = AuditEvent.securityEvent(sid, "mcp-client", "mcp-server", 
                SecurityEventType.MCP_TOOL_INVOKED, "Invoked " + toolName + ": " + detail);
        
        persistenceEngine.submitCritical(PersistenceEvent.critical(sid, SecurityEventType.MCP_TOOL_INVOKED, em -> {
            em.persist(event);
        }));
    }
}
