package com.arcclaw.core.tools;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.persistence.api.CompactionStorePort;
import com.arcclaw.persistence.api.CompactionSummaryDto;
import com.arcclaw.core.util.Fts5QueryUtil;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.jboss.logging.Logger;

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
    CompactionStorePort repository;

    @Override
    public String name() {
        return ToolCapability.MEMORY_SEARCH.toolName();
    }

    @Override
    public String description() {
        return """
            Search the user's long-term memory (past session summaries) for specific keywords,
            topics, or historical context.
            IMPORTANT: Use concrete factual keywords that would appear in stored data —
            e.g. for 'who am I' use query='user name identity', NOT 'your name' or 'my name'.
            For 'what did I work on' use query='project task work'.
            Use noun phrases and keywords, never pronouns or self-referential phrases.
            """;
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.READ_FS;
    }

    @Override
    public IdempotencyMode idempotencyMode() {
        return IdempotencyMode.IDEMPOTENT;
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
        String userId;
        String sessionId;

        try {
            // Step 1 — Extract and validate tenant context
            // NS2: Fail fast on missing context
            userId    = WorkspaceContext.requireUserId();
            sessionId = WorkspaceContext.requireSessionId();
        } catch (IllegalStateException e) {
            // NS2 backward compatibility: convert missing context into 403 ToolResponse
            if (e.getMessage() != null && e.getMessage().contains("unbound")) {
                return ToolResponse.failure(403, "Security violation: tenant context not bound.");
            }
            throw e;
        }

        // 2. Validate query
        String query = params != null ? (String) params.get("query") : null;
        if (query == null || query.isBlank()) {
            return ToolResponse.failure(400, "query parameter is required for memory_search");
        }

        // Normalise self-referential queries to factual equivalents
        query = normaliseMemoryQuery(query);

        String safeQuery = Fts5QueryUtil.toFts5Phrase(query); 

        // 4. Search
        List<CompactionSummaryDto> results;
        try {
            results = repository.search(userId, safeQuery, 5);
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

        // 5. Format results
        if (results == null || results.isEmpty()) {
            return ToolResponse.success("No relevant memory found for query: " + query + 
                                        ". Proceed with current context only.");
        }

        String formatted = results.stream()
                .map((CompactionSummaryDto r) -> String.format("Session %s (%s): %s",
                        r.sessionId(), r.compactedAt(), r.summary()))
                .collect(Collectors.joining("\n---\n"));

        if (formatted.isBlank()) {
            return ToolResponse.success("No relevant memory found for query: " + query + 
                                        ". Proceed with current context only.");
        }

        return ToolResponse.success(formatted);
    }

    private String normaliseMemoryQuery(String query) {
        if (query == null) return query;
        String lower = query.toLowerCase().strip();
        // Self-referential identity queries
        if (lower.matches(".*(my name|who am i|user name|user identity).*")) {
            return "user name identity";
        }
        // Self-referential work queries
        if (lower.matches(".*(what did i|what have i|my work|my project|my task).*")) {
            return "project task work history";
        }
        return query;
    }
}
