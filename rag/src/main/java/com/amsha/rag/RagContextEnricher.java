package com.amsha.rag;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ContextEnricher;
import com.amsha.core.util.Fts5QueryUtil;
import com.amsha.tool.api.AgentSentinels;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RagContextEnricher implements ContextEnricher {

    private static final Logger log = Logger.getLogger(RagContextEnricher.class);

    @ConfigProperty(name = "amsha.rag.max-chunk-chars", defaultValue = "300")
    int maxChunkChars; // ~75 tokens per chunk from defaultValue

    @ConfigProperty(name = "amsha.rag.max-total-chars", defaultValue = "800")
    int maxTotalChars; // ~200 tokens total from defaultValue

    @Inject
    TwoTierMemoryService memoryService;

    @ConfigProperty(name = "amsha.rag.context-chunks", defaultValue = "3")
    int contextChunks;

    // Tune this value relative to what TwoTierMemoryService returns:
    //   vec0 cosine similarity → [0.0, 1.0]
    //   FTS5 BM25 rank         → negative
    //   RRF Fusion             → small positive (e.g. 0.016)
    //
    // Set to -1.0 to disable filtering and trust the top-K ranking.
    @ConfigProperty(name = "amsha.rag.min-score", defaultValue = "0.01")
    double minScore;

    // ── Fix Bug A: write-intent goals must never be enriched with past RAG data.
    // Injecting a previously-stored "My name is srini" chunk into "store my name
    // as srini" causes the LLM to conclude the store already happened and emit
    // "Done" in 1 token - bypassing the ingest_document tool entirely.
    private static final Set<String> WRITE_INTENT_TOKENS = Set.of(
            "store",
            "save",
            "remember",
            "ingest",
            "add to knowledge",
            "call me",
            "note that",
            "add to memory",
            "memorize");

    @Override
    public String enrich(String sessionId, String userId, String goal, String basePrompt) {
        if (goal == null || goal.isBlank()) return basePrompt;

        // §7.45: Skip RAG for cron sessions to prevent scheduling context from
        // leaking into background tasks (causes task refusal).
        // Check sessionId because the CRON prefix may have been stripped from the goal text.
        if (sessionId != null && sessionId.startsWith(AgentSentinels.CRON_SESSION_PREFIX)) {
            log.debugf("RagContextEnricher: skipping - cron session detected (session=%s)", sessionId);
            return basePrompt;
        }

        if (isWriteIntent(goal)) {
            log.debugf("RagContextEnricher: skipping - write-intent goal (session=%s)", sessionId);
            return basePrompt;
        }

        if (userId == null || userId.isBlank()) {
            userId = sessionId;
        }

        try {
            String ftsQuery = Fts5QueryUtil.toFts5Phrase(goal);

            // Fetch more candidates than needed to compute a meaningful distribution (§ recommendation)
            List<RerankedChunk> reranked = memoryService.searchReranked(ftsQuery, userId, contextChunks * 3);
            if (reranked.isEmpty()) return basePrompt;

            List<ScoredChunk> chunks =
                    reranked.stream().map(RerankedChunk::chunk).toList();

            double computedThreshold = minScore;

            // Compute adaptive threshold based on score distribution
            if (chunks.size() >= 3) {
                double mean = chunks.stream()
                        .mapToDouble(ScoredChunk::score)
                        .average()
                        .orElse(0);
                double sumSquares = chunks.stream()
                        .mapToDouble(c -> Math.pow(c.score() - mean, 2))
                        .sum();
                double stdDev = Math.sqrt(sumSquares / chunks.size());

                // Threshold keeps chunks within ~0.5 std-dev below the mean
                double adaptive = mean - (0.5 * stdDev);

                // Final threshold is the max of adaptive and the configured hard floor
                computedThreshold = Math.max(minScore, adaptive);

                log.debugf(
                        "RagContextEnricher: adaptive threshold=%.4f (mean=%.4f, stdDev=%.4f, floor=%.4f) for session %s",
                        (Object) computedThreshold, mean, stdDev, minScore, sessionId);
            } else {
                // Too few samples for distribution - use a modest floor for small KBs (§ recommendation)
                computedThreshold = chunks.size() >= 1 ? 0.0 : minScore;
            }

            final double threshold = computedThreshold;

            List<ScoredChunk> relevant = chunks.stream()
                    .filter(c -> c.score() >= threshold)
                    .limit(contextChunks)
                    .toList();

            if (relevant.isEmpty()) {
                log.debugf(
                        "RagContextEnricher: skipping - 0/%d chunks passed threshold=%.4f for session %s",
                        (Object) chunks.size(), threshold, sessionId);
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

            log.debugf(
                    "RagContextEnricher: injecting %d/%d chunks (threshold=%.4f) for session %s",
                    (Object) relevant.size(), chunks.size(), threshold, sessionId);

            return "## Retrieved Rag Context\n" + context + "\n\n---\n\n" + basePrompt;

        } catch (Exception e) {
            log.warnf("RagContextEnricher enrichment failed for session %s: %s", sessionId, LogUtil.rootMessage(e));
            return basePrompt;
        }
    }

    private boolean isWriteIntent(String goal) {
        if (goal == null) return false;
        String lower = goal.toLowerCase(Locale.ROOT);
        return WRITE_INTENT_TOKENS.stream().anyMatch(lower::contains);
    }
}
