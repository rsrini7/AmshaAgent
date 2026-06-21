package com.arcclaw.core.tools.memory;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.arcclaw.core.agent.reasoning.ArchivalContextCache;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.config.TieredMemoryConfig;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.IdempotencyMode;
import com.arcclaw.core.tools.ToolExecutionContext;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.util.Fts5QueryUtil;
import com.arcclaw.persistence.api.ArchivalChunkDto;
import com.arcclaw.persistence.api.ArchivalMemoryStorePort;
import com.arcclaw.persistence.api.ArchivalSearchQuery;

import org.jboss.logging.Logger;

/**
 * Agent tool for semantic search across long-term archival knowledge.
 *
 * <p>
 * Searches the Archival tier — typed knowledge chunks (CODE, DOC, PATTERN,
 * FACT, PREFERENCE, DIRECTIVE) with BM25 + optional vector hybrid search.
 * <p>
 * After a successful search, results are cached in {@link ArchivalContextCache}
 * for prompt injection via {@code ChatHistoryAssembler} — enforcing the
 * tool-first, never unconditional policy.
 * <p>
 * Tenant isolation is strictly enforced: userId is derived from WorkspaceContext,
 * NEVER from LLM parameters.
 */
@ApplicationScoped
@ToolMetadata(
        capabilityId = "archival_memory_search",
        description = "Searches long-term archival knowledge for patterns, facts, preferences, and code snippets. "
                + "Use this when you need to recall previously stored knowledge (e.g., 'what patterns have we used', "
                + "'project conventions', 'user preferences'). Returns typed chunks with confidence scores.",
        defaultRoles = {"architect", "coordinator", "researcher", "synthesizer", "coder"})
public class ArchivalMemorySearchTool implements AgentTool {

    private static final String PARAM_QUERY = "query";

    private static final Logger log = Logger.getLogger(ArchivalMemorySearchTool.class);

    @Inject
    ArchivalMemoryStorePort archivalStore;

    @Inject
    ArchivalContextCache archivalContextCache;

    @Inject
    TieredMemoryConfig tieredConfig;

    @Inject
    Instance<EmbeddingModel> embeddingModelInstance;

    @Override
    public String name() {
        return ToolCapability.ARCHIVAL_MEMORY_SEARCH.toolName();
    }

    @Override
    public String description() {
        return ToolCapability.ARCHIVAL_MEMORY_SEARCH.description();
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty(PARAM_QUERY, "Keywords or semantic text to search for in archival knowledge")
                .addIntegerProperty("limit", "Max results to return (default from config, typically 3-8)")
                .addStringProperty(
                        "chunk_types",
                        "Comma-separated chunk type filter: CODE, DOC, PATTERN, FACT, PREFERENCE, DIRECTIVE")
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
        try {
            // 1. Resolve tenant
            String userId = WorkspaceContext.getUserIdOrUnknown();

            // 2. Parse params
            String rawQuery = (String) params.get(PARAM_QUERY);
            if (rawQuery == null || rawQuery.isBlank()) {
                return ToolResponse.failure(400, "'query' parameter is required.");
            }

            int defaultLimit = tieredConfig.archival().maxSearchResults();
            int limit = parseLimit(params.get("limit"), defaultLimit);

            Set<String> chunkTypes = parseChunkTypes((String) params.get("chunk_types"));

            // 3. Sanitize query
            String safeQuery = Fts5QueryUtil.toFts5Phrase(rawQuery);

            // 4. Attempt to embed query for hybrid search
            float[] queryEmbedding = null;
            if (embeddingModelInstance != null && !embeddingModelInstance.isUnsatisfied()) {
                try {
                    queryEmbedding = embeddingModelInstance
                            .get()
                            .embed(rawQuery)
                            .content()
                            .vector();
                } catch (Exception e) {
                    log.debugf("Query embedding failed (BM25-only fallback): %s", e.getMessage());
                }
            }

            // 5. Build and execute search query
            ArchivalSearchQuery searchQuery = new ArchivalSearchQuery(
                    userId, safeQuery, limit, chunkTypes, queryEmbedding != null, true, queryEmbedding);

            List<ArchivalChunkDto> results = archivalStore.search(searchQuery);

            // 6. Filter by confidence threshold
            double minConfidence = tieredConfig.archival().confidenceThreshold();
            results = results.stream()
                    .filter(c -> c.confidence() >= minConfidence)
                    .collect(Collectors.toList());

            // 7. Batch increment access count for LRU (single SQL statement)
            if (!results.isEmpty()) {
                try {
                    List<String> resultIds =
                            results.stream().map(ArchivalChunkDto::chunkId).toList();
                    archivalStore.incrementAccessCounts(resultIds);
                } catch (Exception e) {
                    log.debugf("Failed to batch increment access counts: %s", e.getMessage());
                }
            }

            // 8. Format and cache results (don't cache empty results)
            String formatted = formatResults(results);
            if (!results.isEmpty()) {
                archivalContextCache.put(formatted);
            }

            return ToolResponse.success(formatted);

        } catch (Exception e) {
            log.errorf("Archival search failed: %s", e.getMessage());
            return ToolResponse.failure(1, "Archival search error: " + e.getMessage());
        }
    }

    private int parseLimit(Object raw, int defaultLimit) {
        if (raw instanceof Number n) return Math.max(1, Math.min(n.intValue(), 50));
        if (raw instanceof String s) {
            try {
                return Math.max(1, Math.min(Integer.parseInt(s.trim()), 50));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultLimit;
    }

    private Set<String> parseChunkTypes(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(",", -1))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    /**
     * Format archival results as compact text for prompt injection and agent display.
     */
    private String formatResults(List<ArchivalChunkDto> results) {
        if (results.isEmpty()) {
            return "No relevant archival knowledge found.";
        }

        var sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            ArchivalChunkDto chunk = results.get(i);
            if (i > 0) sb.append("\n\n");
            sb.append("### [").append(chunk.chunkType()).append("]");
            if (chunk.sourceSession() != null) {
                sb.append(" (from ").append(chunk.sourceSession()).append(")");
            }
            sb.append(" confidence=").append(String.format("%.2f", chunk.confidence()));
            sb.append("\n").append(chunk.content());
        }
        return sb.toString();
    }
}
