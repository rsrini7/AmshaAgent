package com.arcclaw.mcp.server;

import com.arcclaw.core.audit.AuditEvent;
import com.arcclaw.core.audit.AuditEventRepository;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.persistence.AsyncPersistenceEngine;
import com.arcclaw.core.persistence.PersistenceEvent;
import com.arcclaw.mcp.bridge.McpToolBridge;

import io.quarkiverse.mcp.server.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class McpToolsBean {
    //private static final Logger log = Logger.getLogger(McpToolsBean.class);

    @Inject McpToolBridge bridge;
    @Inject AsyncPersistenceEngine persistenceEngine;
    @Inject AuditEventRepository auditEventRepository;

    @Tool(name = "github", description = "Execute GitHub operations (search, pull, comment)")
    public String github(String operation, String paramsJson) {
        audit("github", operation + " " + paramsJson);
        return bridge.invoke("github", paramsJson);
    }

    @Tool(name = "memory_search", description = "Search long-term episodic and semantic memory")
    public String memorySearch(String query) {
        audit("memory_search", query);
        String params = "{\"query\":\"" + query.replace("\"", "\\\"") + "\"}";
        return bridge.invoke("memory_search", params);
    }

    private void audit(String toolName, String detail) {
        String sid = "mcp-external";

        AuditEvent event = AuditEvent.securityEvent(sid, "mcp-client", "mcp-server",
                SecurityEventType.MCP_TOOL_INVOKED, "Invoked " + toolName + ": " + detail);

        // Use auditEventRepository.record() — NOT em.persist() directly.
        // AuditEvent.securityEvent() leaves hmacHash and prevHash null.
        // record() is the only place that computes the HMAC chain and sets both fields
        // before persisting. Calling em.persist() directly bypasses that computation
        // and causes a NOT NULL constraint violation on audit_event.hmacHash.
        persistenceEngine.submitCritical(PersistenceEvent.critical(sid, SecurityEventType.MCP_TOOL_INVOKED, em -> {
            auditEventRepository.record(event);
        }));
    }
}