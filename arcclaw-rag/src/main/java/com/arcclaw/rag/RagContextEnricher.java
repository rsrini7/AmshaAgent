package com.arcclaw.rag;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ContextEnricher;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.util.Fts5QueryUtil;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RagContextEnricher implements ContextEnricher {

    private static final Logger log = Logger.getLogger(RagContextEnricher.class);
    private static final int MAX_CHUNK_CHARS = 300; // ~75 tokens per chunk
    private static final int MAX_TOTAL_CONTEXT_CHARS = 800; // ~200 tokens total

    @Inject TwoTierMemoryService memoryService;

    @ConfigProperty(name = "arcclaw.rag.context-chunks", defaultValue = "3")
    int contextChunks;

    // ── Fix Bug D: without a score floor, a single document ("My name is srini")
    // is returned for every query regardless of relevance (weather, code, etc.).
    // Tune this value relative to what TwoTierMemoryService returns:
    //   vec0 cosine similarity → [0.0, 1.0]  → 0.55 is a good floor
    //   FTS5 BM25 rank         → negative    → set arcclaw.rag.min-score=-1.0 to disable
    @ConfigProperty(name = "arcclaw.rag.min-score", defaultValue = "0.55")
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

        if (isWriteIntent(goal)) {
            log.debugf("RagContextEnricher: skipping — write-intent goal (session=%s)", sessionId);
            return basePrompt;
        }

        String userId = WorkspaceContext.userId();
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
                    .map(t -> t.length() > MAX_CHUNK_CHARS ? t.substring(0, MAX_CHUNK_CHARS) + "…" : t)
                    .collect(Collectors.joining("\n---\n"));

            if (context.isBlank()) return basePrompt;

            if (context.length() > MAX_TOTAL_CONTEXT_CHARS) {
                context = context.substring(0, MAX_TOTAL_CONTEXT_CHARS) + "\n…[truncated]";
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