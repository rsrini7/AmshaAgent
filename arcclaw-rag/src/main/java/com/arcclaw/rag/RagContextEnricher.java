package com.arcclaw.rag;

import com.arcclaw.core.agent.reasoning.ContextEnricher;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class RagContextEnricher implements ContextEnricher {

    private static final Logger log = Logger.getLogger(RagContextEnricher.class);

    @Inject TwoTierMemoryService memoryService;

    @ConfigProperty(name = "arcclaw.rag.context-chunks", defaultValue = "5")
    int contextChunks;

    @Override
    public String enrich(String sessionId, String goal, String basePrompt) {
        if (goal == null || goal.isBlank()) return basePrompt;

        // Use WorkspaceScopeHolder.USER_ID (ScopedValue)
        String userId = WorkspaceScopeHolder.USER_ID.isBound()
            ? WorkspaceScopeHolder.USER_ID.get() : null;
        if (userId == null) return basePrompt;

        try {
            List<ScoredChunk> chunks = memoryService.search(userId, goal, contextChunks);
            if (chunks.isEmpty()) return basePrompt;

            String context = chunks.stream()
                .map(ScoredChunk::text)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n---\n"));

            if (context.isBlank()) return basePrompt;

            return "## Retrieved Context\n" + context + "\n\n---\n\n" + basePrompt;

        } catch (Exception e) {
            log.warnf("RagContextEnricher enrichment failed for session %s: %s",
                sessionId, e.getMessage());
            return basePrompt; // Always fail open — never crash the agent loop
        }
    }
}
