package com.amsha.tools.builtin.memory;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.agent.role.ToolMetadata;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.tools.AgentTool;
import com.amsha.core.tools.IdempotencyMode;
import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.core.tools.ToolResponse;
import com.amsha.core.util.Fts5QueryUtil;
import com.amsha.persistence.api.RecallMemoryStorePort;
import com.amsha.persistence.api.RecallMessageDto;
import com.amsha.persistence.api.RecallSearchQuery;
import com.amsha.security.api.ToolOperation;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

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
@Unremovable
@ToolMetadata(
        capabilityId = "recall_memory_search",
        description =
                "Searches EXACT conversation history for specific code snippets, error traces, or past decisions. "
                        + "Use this when you need to recall exact details (e.g., 'the stack trace from yesterday', 'the Python function we wrote'). "
                        + "Does NOT search high-level summaries unless explicitly requested with include_summaries=true.",
        defaultRoles = {"architect", "coordinator", "researcher", "synthesizer", "coder", "debugger"})
public class RecallMemorySearchTool implements AgentTool {

    private static final Logger log = Logger.getLogger(RecallMemorySearchTool.class);
    private static final String PARAM_QUERY = "query";
    private static final String PARAM_SESSION_ID = "session_id";

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
            For semantic search across long-term knowledge, use archival_memory_search instead.
            """;
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty(PARAM_QUERY, "Keywords or exact text to search for in conversation history")
                .addStringProperty(
                        PARAM_SESSION_ID,
                        "Session ID to scope search (default: current session). "
                                + "Pass 'all' or '*' to search across all sessions for this user.")
                .addBooleanProperty("include_summaries", "Include compaction summaries in results (default: false)")
                .addIntegerProperty("limit", "Maximum results to return (default: 5)")
                .required(PARAM_QUERY)
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
        String query = (String) params.get(PARAM_QUERY);
        if (query == null || query.isBlank()) {
            return ToolResponse.failure(400, "query parameter is required");
        }

        String userId = WorkspaceContext.getUserIdOrUnknown();
        if (userId == null || userId.isBlank()) {
            return ToolResponse.failure(403, "tenant context not available");
        }

        // Handle session_id: missing=current, null/"all"/"*"=cross-session, explicit value=specific session
        String sessionId;
        if (!params.containsKey(PARAM_SESSION_ID)) {
            sessionId = ctx.sessionId();
        } else {
            String val = (String) params.get(PARAM_SESSION_ID);
            if (val == null || "all".equalsIgnoreCase(val) || "*".equals(val)) {
                sessionId = null;
            } else {
                sessionId = val;
            }
        }

        boolean includeSummaries = parseBoolean(params.get("include_summaries"), false);
        int limit = parseLimit(params.get("limit"), 5);

        // Sanitize query for FTS5 (SQLite) and plainto_tsquery (Postgres).
        // toFts5Phrase strips special chars (+, (), :, etc.) to prevent FTS5 syntax errors.
        // PostgreSQL plainto_tsquery is safe by design, but tool layer doesn't know backend
        // (abstraction boundary). Punctuation loss on Postgres is acceptable tradeoff.
        String safeQuery = Fts5QueryUtil.toFts5Phrase(query);
        log.debugf(
                "Recall search: userId=%s, sessionId=%s, query=%s (sanitized from %s), includeSummaries=%s, limit=%d",
                userId, sessionId, safeQuery, query, includeSummaries, limit);

        RecallSearchQuery searchQuery =
                new RecallSearchQuery(userId, safeQuery, sessionId, limit, includeSummaries, Set.of());

        List<RecallMessageDto> results = recallStore.search(searchQuery);

        if (results.isEmpty()) {
            log.debugf("Recall search returned no results for query: %s", query);
            return ToolResponse.success("No exact matches found in conversation history for: " + query);
        }

        log.debugf("Recall search found %d results for query: %s", results.size(), query);

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" exact matches:\n\n");
        for (RecallMessageDto msg : results) {
            sb.append("### [Session ")
                    .append(msg.sessionId())
                    .append(" | Turn ")
                    .append(msg.turnNumber())
                    .append(" | ")
                    .append(msg.role())
                    .append("]\n");
            sb.append(msg.content()).append("\n\n");
        }
        return ToolResponse.success(sb.toString());
    }

    /** Accepts JSON numbers or LLM-emitted digit strings; clamps to [1, 50]. */
    private static int parseLimit(Object raw, int defaultLimit) {
        if (raw instanceof Number n) {
            return Math.max(1, Math.min(n.intValue(), 50));
        }
        if (raw instanceof String s) {
            try {
                return Math.max(1, Math.min(Integer.parseInt(s.trim()), 50));
            } catch (NumberFormatException _) {
                // fall through
            }
        }
        return defaultLimit;
    }

    /** Accepts JSON booleans or common LLM string forms ("true"/"false"). */
    private static boolean parseBoolean(Object raw, boolean defaultValue) {
        return switch (raw) {
            case null -> defaultValue;
            case Boolean b -> b;
            case String s -> Boolean.parseBoolean(s.trim());
            default -> defaultValue;
        };
    }
}
