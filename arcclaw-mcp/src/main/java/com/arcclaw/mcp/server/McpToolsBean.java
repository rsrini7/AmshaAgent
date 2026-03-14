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
        String params = "{\"query\": \"" + query.replace("\"", "\\\"") + "\"}";
        return bridge.invoke("memory_search", params);
    }

    private void audit(String toolName, String detail) {
        String sid = "mcp-external";
        AuditEvent event = AuditEvent.securityEvent(
            sid, "mcp-client", "mcp-server",
            SecurityEventType.TOOL_INVOKED,
            "Invoked " + toolName + " " + detail);
        persistenceEngine.submitCritical(
            PersistenceEvent.critical(sid, SecurityEventType.TOOL_INVOKED,
                em -> auditEventRepository.record(event)));
    }
}