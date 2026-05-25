package com.arcclaw.core.tools.memory;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.IdempotencyMode;
import com.arcclaw.core.tools.ToolExecutionContext;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.util.Fts5QueryUtil;
import com.arcclaw.persistence.api.RecallMemoryStorePort;
import com.arcclaw.persistence.api.RecallMessageDto;
import com.arcclaw.persistence.api.RecallSearchQuery;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.jboss.logging.Logger;

/**
 * Agent tool for searching EXACT conversation history (raw messages, code snippets, error traces).
 * <p>
 * This tool searches the Recall tier — preserved raw messages from past turns.
 * It does NOT search compaction summaries or RAG knowledge by default.
 * <p>
 * Tenant isolation is strictly enforced: userId is derived from WorkspaceContext,
 * NEVER from LLM parameters. Cross-session search remains scoped to the same tenant.
 */
@ApplicationScoped
@ToolMetadata(
    capabilityId = "recall_memory_search",
    description = "Searches EXACT conversation history for specific code snippets, error traces, or past decisions. " +
                  "Use this when you need to recall exact details (e.g., 'the stack trace from yesterday', 'the Python function we wrote'). " +
                  "Does NOT search high-level summaries unless explicitly requested with include_summaries=true.",
    defaultRoles = {"architect", "coordinator", "researcher", "synthesizer", "coder"}
)
public class RecallMemorySearchTool implements AgentTool {

    private static final Logger log = Logger.getLogger(RecallMemorySearchTool.class);

    @Inject
    RecallMemoryStorePort recallStore;

    @Override
    public String name() {
        return ToolCapability.RECALL_MEMORY_SEARCH.toolName();
    }

    @Override
    public String description() {
        return """
            Search exact conversation history (raw messages, code, errors) for specific keywords or phrases.
            Use when you need to find exact text that appeared in prior turns — code snippets, error messages,
            specific decisions, or user statements. Session-scoped by default for precise recall.
            Does NOT search session summaries or RAG documents by default (use include_summaries=true).
            For semantic search across summaries only, use memory_search instead.
            """;
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
            .addStringProperty("query", "Keywords or exact text to search for in conversation history")
            .addStringProperty("session_id", 
                "Session ID to scope search (default: current session). " +
                "Pass 'all' or '*' to search across all sessions for this user.")
            .addBooleanProperty("include_summaries", "Include compaction summaries in results (default: false)")
            .addIntegerProperty("limit", "Maximum results to return (default: 5)")
            .required("query")
            .build();
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
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String query = (String) params.get("query");
        if (query == null || query.isBlank()) {
            return ToolResponse.failure(400, "query parameter is required");
        }

        String userId = WorkspaceContext.getUserIdOrUnknown();
        if (userId == null || userId.isBlank()) {
            return ToolResponse.failure(403, "tenant context not available");
        }

        // Handle session_id: missing=current, null/"all"/"*"=cross-session, explicit value=specific session
        String sessionId;
        if (!params.containsKey("session_id")) {
            sessionId = ctx.sessionId();
        } else {
            String val = (String) params.get("session_id");
            if (val == null || "all".equalsIgnoreCase(val) || "*".equals(val)) {
                sessionId = null;
            } else {
                sessionId = val;
            }
        }
        
        boolean includeSummaries = Boolean.TRUE.equals(params.get("include_summaries"));
        int limit = params.containsKey("limit") ? ((Number) params.get("limit")).intValue() : 5;

        // Sanitize query for FTS5 (SQLite) and plainto_tsquery (Postgres).
        // toFts5Phrase strips special chars (+, (), :, etc.) to prevent FTS5 syntax errors.
        // PostgreSQL plainto_tsquery is safe by design, but tool layer doesn't know backend
        // (abstraction boundary). Punctuation loss on Postgres is acceptable tradeoff.
        String safeQuery = Fts5QueryUtil.toFts5Phrase(query);
        log.debugf("Recall search: userId=%s, sessionId=%s, query=%s (sanitized from %s), includeSummaries=%s, limit=%d",
                   userId, sessionId, safeQuery, query, includeSummaries, limit);

        RecallSearchQuery searchQuery = new RecallSearchQuery(
            userId, safeQuery, sessionId, limit, includeSummaries, Set.of()
        );

        List<RecallMessageDto> results = recallStore.search(searchQuery);

        if (results.isEmpty()) {
            log.debugf("Recall search returned no results for query: %s", query);
            return ToolResponse.success("No exact matches found in conversation history for: " + query);
        }

        log.debugf("Recall search found %d results for query: %s", results.size(), query);

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" exact matches:\n\n");
        for (RecallMessageDto msg : results) {
            sb.append("### [Session ").append(msg.sessionId())
              .append(" | Turn ").append(msg.turnNumber())
              .append(" | ").append(msg.role()).append("]\n");
            sb.append(msg.content()).append("\n\n");
        }
        return ToolResponse.success(sb.toString());
    }
}
