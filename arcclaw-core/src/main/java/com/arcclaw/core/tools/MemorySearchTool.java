package com.arcclaw.core.tools;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.state.CompactionSummaryEntity;
import com.arcclaw.core.state.CompactionSummaryRepository;
import com.arcclaw.core.agent.role.ToolCapability;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent tool for searching user's long-term memory (past session summaries)
 * via SQLite FTS5 full-text search.
 * <p>
 * Tenant isolation is enforced by extracting userId from the ScopedValue,
 * never from LLM parameters. The LLM can only control the search query.
 */
@ApplicationScoped
public class MemorySearchTool implements AgentTool {

    private static final Logger log = Logger.getLogger(MemorySearchTool.class);

    @Inject
    CompactionSummaryRepository repository;

    @Inject
    AuditService auditService;

    @Override
    public String name() {
        return ToolCapability.MEMORY_SEARCH.toolName();
    }

    @Override
    public String description() {
        return "Search the user's long-term memory (past session summaries) for specific keywords, " +
               "topics, or historical context. Use this when the user asks about past decisions, " +
               "previous sessions, or historical context.";
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.READ_FS;
    }

    @Override
    public boolean requiresSandbox() {
        return false;
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
            .addStringProperty("query", "Keywords to search for")
            .required("query")
            .build();
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        try {
            // 1. Extract userId from ScopedValue — hard tenant boundary
            String userId;
            if (!WorkspaceScopeHolder.USER_ID.isBound()) {
                throw new SecurityException("memory_search: USER_ID not bound in current scope");
            }
            userId = WorkspaceScopeHolder.USER_ID.get();
            if (userId == null || userId.isBlank()) {
                throw new SecurityException("memory_search: USER_ID not bound in current scope");
            }

            // 2. Extract sessionId for audit
            String sessionId = WorkspaceScopeHolder.getIfActive();

            // 3. Validate query
            String query = params != null ? (String) params.get("query") : null;
            if (query == null || query.isBlank()) {
                return ToolResponse.success("No query provided");
            }

            // 4. Search
            List<CompactionSummaryEntity> results;
            try {
                results = repository.searchMemory(userId, query, 5);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (msg.contains("no such table") || msg.contains("fts")) {
                    log.warnf("FTS index unavailable for session %s: %s", sessionId, msg);
                    return ToolResponse.success(
                        "Memory index temporarily unavailable. Proceed with current context.");
                }
                log.errorf("MemorySearchTool failed for session %s: %s", sessionId, msg);
                log.debugf(e, "Full stacktrace for MemorySearchTool failure in session %s", sessionId);
                return ToolResponse.failure(500, "Memory search failed: " + msg);
            }

            // 5. Audit
            auditService.record(sessionId, userId, "tool",
                    SecurityEventType.TOOL_INVOKED,
                    "tool=memory_search query_length=" + query.length());

            // 6. Format results
            if (results == null || results.isEmpty()) {
                return ToolResponse.success("No relevant memory found for query: " + query + 
                                            ". Proceed with current context only.");
            }

            String formatted = results.stream()
                    .map(r -> String.format("Session %s (%s): %s",
                            r.sessionId, r.createdAt, r.summary))
                    .collect(Collectors.joining("\n---\n"));

            if (formatted.isBlank()) {
                return ToolResponse.success("No relevant memory found for query: " + query + 
                                            ". Proceed with current context only.");
            }

            return ToolResponse.success(formatted);

        } catch (SecurityException e) {
            throw e; // re-throw security exceptions — must not be swallowed
        }
    }
}
