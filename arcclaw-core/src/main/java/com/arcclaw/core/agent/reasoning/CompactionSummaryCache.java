package com.arcclaw.core.agent.reasoning;

import java.util.Optional;

import jakarta.inject.Inject;

import com.arcclaw.core.scope.WorkspaceScoped;
import com.arcclaw.core.state.CompactionSummaryEntity;
import com.arcclaw.core.state.CompactionSummaryRepository;
import org.jboss.logging.Logger;

/**
 * Caches the compaction summary for the duration of a workspace session.
 * <p>
 * This prevents redundant database hits during the multi-turn agent loop,
 * as the system prompt (which includes the summary) is rebuilt on every LLM call.
 * Since compaction only happens before the agent loop starts, the summary
 * is guaranteed to be stable during the loop iteration.
 */
@WorkspaceScoped
public class CompactionSummaryCache {

    private static final Logger log = Logger.getLogger(CompactionSummaryCache.class);

    private String cachedSummary;
    private boolean loaded = false;

    @Inject
    CompactionSummaryRepository summaryRepository;

    /**
     * Returns the cached summary for the given sessionId, or fetches it from
     * the repository if not already cached.
     */
    public Optional<String> getSummary(String sessionId) {
        if (!loaded) {
            log.tracef("CompactionSummaryCache: cache miss for session %s, fetching from DB", sessionId);
            cachedSummary = summaryRepository.findLatestBySessionId(sessionId)
                    .map(CompactionSummaryEntity::getSummary)
                    .orElse(null);
            loaded = true;
        } else {
            log.tracef("CompactionSummaryCache: cache hit for session %s", sessionId);
        }
        return Optional.ofNullable(cachedSummary);
    }

    /**
     * Explicitly invalidates the cache.
     * Use this when a new compaction has been completed for this session.
     */
    public void invalidate() {
        if (loaded) {
            log.debug("CompactionSummaryCache: invalidating cache");
            this.cachedSummary = null;
            this.loaded = false;
        }
    }
}
