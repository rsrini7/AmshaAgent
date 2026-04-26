package com.arcclaw.core.tools;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.IntentClassificationService;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.util.Fts5QueryUtil;
import com.arcclaw.persistence.api.CompactionStorePort;
import com.arcclaw.persistence.api.CompactionSummaryDto;
import com.arcclaw.persistence.api.MemoryChunkDto;
import com.arcclaw.persistence.api.MemoryRetrievalPort;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.jboss.logging.Logger;

/**
 * Agent tool for searching user's long-term memory (past session summaries)
 * and the RAG knowledge base (ingested documents).
 * <p>
 * Tenant isolation is enforced by extracting userId from the ScopedValue,
 * never from LLM parameters. The LLM can only control the search query.
 */
@ApplicationScoped
public class MemorySearchTool implements AgentTool {

    private static final Logger log = Logger.getLogger(MemorySearchTool.class);

    @Inject
    CompactionStorePort compactionRepo;

    @Inject
    MemoryRetrievalPort memoryPort;

    @Inject
    IntentClassificationService intentService;

    @Override
    public String name() {
        return ToolCapability.MEMORY_SEARCH.toolName();
    }

    @Override
    public String description() {
        return """
            Search the user's long-term memory (past session summaries) and the RAG knowledge
            base (ingested documents) for specific keywords, topics, or historical context.
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
    public boolean requiresManualApproval(Map<String, Object> params) {
        return true;
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        String userId;
        String sessionId;

        try {
            // NS2: Extract and validate tenant context
            userId    = WorkspaceContext.requireUserId();
            sessionId = WorkspaceContext.requireSessionId();
        } catch (IllegalStateException e) {
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

        // 4. Search across BOTH tiers
        StringBuilder combined = new StringBuilder();

        // TIER A: Compaction Summaries (FTS5)
        try {
            List<CompactionSummaryDto> summaries = compactionRepo.search(userId, safeQuery, 5);
            if (summaries != null && !summaries.isEmpty()) {
                String formatted = summaries.stream()
                        .map(r -> String.format("[Session %s @ %s]: %s",
                                r.sessionId(), r.compactedAt(), r.summary()))
                        .collect(Collectors.joining("\n---\n"));
                combined.append("### Relevant Session Summaries:\n").append(formatted).append("\n\n");
            }
        } catch (Exception e) {
            log.warnf("Compaction search failed for session %s: %s", sessionId, e.getMessage());
        }

        // TIER B: RAG Knowledge Base (Vector + FTS5)
        try {
            List<MemoryChunkDto> chunks = memoryPort.search(safeQuery, 5, userId);
            if (chunks != null && !chunks.isEmpty()) {
                String formatted = chunks.stream()
                        .map(MemoryChunkDto::text)
                        .filter(t -> t != null && !t.isBlank())
                        .collect(Collectors.joining("\n---\n"));
                if (!formatted.isBlank()) {
                    combined.append("### Relevant Ingested Documents:\n").append(formatted).append("\n\n");
                }
            }
        } catch (Exception e) {
            log.warnf("RAG search failed for session %s: %s", sessionId, e.getMessage());
        }

        // 5. Format final result
        if (combined.isEmpty()) {
            return ToolResponse.success("No relevant memory found for query: " + query + 
                                        ". Proceed with current context only.");
        }

        return ToolResponse.success(combined.toString().trim());
    }

    private String normaliseMemoryQuery(String query) {
        if (query == null) return query;
        String lower = query.toLowerCase().strip();
        // Self-referential identity queries
        if (intentService.isIdentityQuery(lower)) {
            return "user name identity";
        }
        // Self-referential work queries
        if (lower.matches(".*(what did i|what have i|my work|my project|my task).*")) {
            return "project task work history";
        }
        return query;
    }
}
