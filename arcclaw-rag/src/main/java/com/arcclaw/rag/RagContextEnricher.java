package com.arcclaw.rag;

import java.util.List;
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

    @Inject TwoTierMemoryService memoryService;

    @ConfigProperty(name = "arcclaw.rag.context-chunks", defaultValue = "5")
    int contextChunks;

    @Override
    public String enrich(String sessionId, String goal, String basePrompt) {
        if (goal == null || goal.isBlank()) return basePrompt;

        // WorkspaceContext.userId() returns "" when unbound — never null.
        // ArcClaw is single-tenant via Telegram; userId is the chatId string.
        // Fall back to sessionId so FTS5 and vec searches still execute even
        // when userId is not explicitly propagated into the scope.
        String userId = WorkspaceContext.userId();
        if (userId == null || userId.isBlank()) {
            userId = sessionId;
        }

        try {
            // Wrap in FTS5 phrase quotes to handle apostrophes and special characters
            // (e.g. "today's news" → "\"today's news\"").
            // Without quoting, FTS5 parses the value as a query expression and
            // an apostrophe opens an unterminated phrase literal → SQLITE_ERROR.
            // Phrase search is also semantically better for RAG — we want the
            // whole phrase matched, not individual unordered tokens.
            String ftsQuery = Fts5QueryUtil.toFts5Phrase(goal);

            List<ScoredChunk> chunks = memoryService.search(ftsQuery, userId, contextChunks);
            if (chunks.isEmpty()) return basePrompt;

            String context = chunks.stream()
                .map(ScoredChunk::text)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n---\n"));

            if (context.isBlank()) return basePrompt;

            log.debugf("RagContextEnricher: injecting %d chunks for session %s",
                chunks.size(), sessionId);

            return "## Retrieved Context\n" + context + "\n\n---\n\n" + basePrompt;

        } catch (Exception e) {
            // Always fail open — a RAG failure must never crash the agent loop.
            // No permanent disable flag — transient Jina/network failures are
            // retried on the next message automatically.
            log.warnf("RagContextEnricher enrichment failed for session %s: %s",
                sessionId, e.getMessage());
            return basePrompt;
        }
    }

}
