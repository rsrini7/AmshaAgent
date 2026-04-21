package com.arcclaw.rag;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ContextEnricher;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.util.Fts5QueryUtil;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RagContextEnricher implements ContextEnricher {

    private static final Logger log = Logger.getLogger(RagContextEnricher.class);
    @ConfigProperty(name = "arcclaw.rag.max-chunk-chars", defaultValue = "300")
    int maxChunkChars; // ~75 tokens per chunk from defaultValue

    @ConfigProperty(name = "arcclaw.rag.max-total-chars", defaultValue = "800")
    int maxTotalChars; // ~200 tokens total from defaultValue

    @Inject TwoTierMemoryService memoryService;

    @ConfigProperty(name = "arcclaw.rag.context-chunks", defaultValue = "3")
    int contextChunks;

    // Tune this value relative to what TwoTierMemoryService returns:
    //   vec0 cosine similarity → [0.0, 1.0]
    //   FTS5 BM25 rank         → negative
    //   RRF Fusion             → small positive (e.g. 0.016)
    //
    // Set to -1.0 to disable filtering and trust the top-K ranking.
    @ConfigProperty(name = "arcclaw.rag.min-score", defaultValue = "-1.0")
    double minScore;

    // ── Fix Bug A: write-intent goals must never be enriched with past RAG data.
    // Injecting a previously-stored "My name is srini" chunk into "store my name
    // as srini" causes the LLM to conclude the store already happened and emit
    // "Done" in 1 token — bypassing the ingest_document tool entirely.
    private static final Set<String> WRITE_INTENT_TOKENS = Set.of(
            "store", "save", "remember", "ingest", "add to knowledge",
            "call me", "note that", "add to memory", "memorize"
    );

    @Override
    public String enrich(String sessionId, String goal, String basePrompt) {
        if (goal == null || goal.isBlank()) return basePrompt;

        // §7.45: Skip RAG for cron sessions to prevent scheduling context from
        // leaking into background tasks (causes task refusal).
        if (AgentSentinels.isCronGoal(goal)) {
            log.debugf("RagContextEnricher: skipping — cron session detected (session=%s)", sessionId);
            return basePrompt;
        }

        if (isWriteIntent(goal)) {
            log.debugf("RagContextEnricher: skipping — write-intent goal (session=%s)", sessionId);
            return basePrompt;
        }

        String userId = WorkspaceContext.requireUserId();
        if (userId == null || userId.isBlank()) {
            userId = sessionId;
        }

        try {
            String ftsQuery = Fts5QueryUtil.toFts5Phrase(goal);

            List<ScoredChunk> chunks = memoryService.search(ftsQuery, userId, contextChunks);
            if (chunks.isEmpty()) return basePrompt;

            List<ScoredChunk> relevant = chunks.stream()
                    .filter(c -> c.score() >= minScore)
                    .toList();

            if (relevant.isEmpty()) {
                log.debugf("RagContextEnricher: injecting %d/%d chunks (min-score=%.2f) for session %s",
                    (Object) relevant.size(), chunks.size(), minScore, sessionId);
                return basePrompt;
            }

            String context = relevant.stream()
                    .map(ScoredChunk::text)
                    .filter(t -> t != null && !t.isBlank())
                    .map(t -> t.length() > maxChunkChars ? t.substring(0, maxChunkChars) + "…" : t)
                    .collect(Collectors.joining("\n---\n"));

            if (context.isBlank()) return basePrompt;

            if (context.length() > maxTotalChars) {
                context = context.substring(0, maxTotalChars) + "\n…[truncated]";
            }

            log.debugf("RagContextEnricher: injecting %d/%d chunks (min-score=%.2f) for session %s",
                    (Object) relevant.size(), chunks.size(), minScore, sessionId);

            return "## Retrieved Rag Context\n" + context + "\n\n---\n\n" + basePrompt;

        } catch (Exception e) {
            log.warnf("RagContextEnricher enrichment failed for session %s: %s",
                    sessionId, e.getMessage());
            return basePrompt;
        }
    }

    private boolean isWriteIntent(String goal) {
        if (goal == null) return false;
        String lower = goal.toLowerCase();
        return WRITE_INTENT_TOKENS.stream().anyMatch(lower::contains);
    }
}
