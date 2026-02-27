package com.arcclaw.core.tools;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.state.CompactionSummaryEntity;
import com.arcclaw.core.state.CompactionSummaryRepository;

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
        return "memory_search";
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
            List<CompactionSummaryEntity> results = repository.searchMemory(userId, query, 5);

            // 5. Audit
            auditService.record(sessionId, userId, "tool",
                    SecurityEventType.TOOL_INVOKED,
                    "tool=memory_search query_length=" + query.length());

            // 6. Format results
            if (results.isEmpty()) {
                return ToolResponse.success("No relevant past sessions found for: " + query);
            }

            String formatted = results.stream()
                    .map(r -> String.format("Session %s (%s): %s",
                            r.sessionId, r.createdAt, r.summary))
                    .collect(Collectors.joining("\n---\n"));

            return ToolResponse.success(formatted);

        } catch (SecurityException e) {
            throw e; // re-throw security exceptions — must not be swallowed
        } catch (Exception e) {
            String sessionId = WorkspaceScopeHolder.getIfActive();
            log.warnf(e, "[MemorySearchTool] Search failed for session=%s", sessionId);
            return ToolResponse.success("Memory search failed: " + e.getMessage());
        }
    }
}
