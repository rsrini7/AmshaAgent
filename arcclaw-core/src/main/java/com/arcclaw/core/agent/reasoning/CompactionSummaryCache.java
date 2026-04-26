package com.arcclaw.core.agent.reasoning;

import java.util.Optional;

import jakarta.inject.Inject;

import com.arcclaw.core.scope.WorkspaceScoped;
import com.arcclaw.core.state.CompactionEventBridge;
import com.arcclaw.persistence.api.CompactionStorePort;
import com.arcclaw.persistence.api.CompactionSummaryDto;
import org.jboss.logging.Logger;

/**
 * Caches the compaction summary for the duration of a workspace session.
 * <p>
 * This prevents redundant database hits during the multi-turn agent loop,
 * as the system prompt (which includes the summary) is rebuilt on every LLM call.
 * <p>
 * <strong>Compaction race guard:</strong> On the first fetch, this cache calls
 * {@link CompactionEventBridge#awaitIfInFlight(String)} before querying the DB.
 * This guarantees the cache is populated AFTER any in-flight compaction has
 * committed its summary - preventing a stale-null scenario where the cache
 * loads before compaction writes and then serves {@code null} for the rest of
 * the session.
 * <p>
 * <strong>Invalidation:</strong> Because compaction runs once before the agent
 * loop starts (CompactionHandler priority 60 < AgentDispatchHandler priority 70),
 * and the cache awaits the compaction latch on first load, explicit
 * {@code invalidate()} is normally not needed. It is provided for paranoia and
 * potential future mid-session re-compaction scenarios.
 */
@WorkspaceScoped
public class CompactionSummaryCache {

    private static final Logger log = Logger.getLogger(CompactionSummaryCache.class);

    private String cachedSummary;
    private boolean loaded = false;

    @Inject
    CompactionStorePort summaryRepository;

    @Inject
    CompactionEventBridge compactionEventBridge;

    /**
     * Returns the cached summary for the given sessionId, or fetches it from
     * the repository if not already cached.
     * <p>
     * On the first call, blocks until any in-flight compaction for this session
     * has committed, then queries the DB. All subsequent calls return the
     * cached value with no I/O.
     */
    public Optional<String> getSummary(String sessionId) {
        if (!loaded) {
            // Wait for any in-flight compaction to finish before reading.
            // Without this, the first nextStep() call in the agent loop can
            // query the DB while CompactionService.compact() is still writing,
            // caching null and serving stale data for the rest of the session.
            compactionEventBridge.awaitIfInFlight(sessionId);

            log.tracef("CompactionSummaryCache: cache miss for session %s, fetching from DB", sessionId);
            cachedSummary = summaryRepository.findLatestBySessionId(sessionId)
                    .map(CompactionSummaryDto::summary)
                    .orElse(null);
            loaded = true;
        } else {
            log.tracef("CompactionSummaryCache: cache hit for session %s", sessionId);
        }
        return Optional.ofNullable(cachedSummary);
    }

    /**
     * Explicitly invalidates the cache.
     * <p>
     * Use this when a new compaction has been completed for this session
     * and the next prompt build should pick up the fresh summary.
     */
    public void invalidate() {
        if (loaded) {
            log.debug("CompactionSummaryCache: invalidating cache");
            this.cachedSummary = null;
            this.loaded = false;
        }
    }
}
