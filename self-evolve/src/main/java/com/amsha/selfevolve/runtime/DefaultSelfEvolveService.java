package com.amsha.selfevolve.runtime;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.amsha.common.util.LogUtil;
import com.amsha.core.config.SelfEvolveConfig;
import com.amsha.selfevolve.api.EvolutionEvent;
import com.amsha.selfevolve.api.EvolutionEventType;
import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.FlushReason;
import com.amsha.selfevolve.buffer.ConversationBuffer;
import com.amsha.selfevolve.buffer.InMemoryConversationBuffer;
import com.amsha.selfevolve.extract.MemoryExtractorLite;
import com.amsha.selfevolve.store.DirectiveStorePort;
import com.amsha.selfevolve.store.EvolutionEventStorePort;
import com.amsha.selfevolve.store.ExtractedMemoryStorePort;
import com.amsha.selfevolve.store.noop.NoOpDirectiveStore;
import com.amsha.selfevolve.store.noop.NoOpEvolutionEventStore;
import com.amsha.selfevolve.store.noop.NoOpExtractedMemoryStore;

import org.jboss.logging.Logger;

import io.quarkus.runtime.Startup;

/**
 * Default implementation of self-evolution trace capture service.
 * Manages per-session conversation buffers, event emission, and flush lifecycle.
 * <p>
 * When {@code amsha.self-evolve.enabled=false}, all operations are no-ops.
 */
@Startup
@ApplicationScoped
public class DefaultSelfEvolveService {

    private static final Logger log = Logger.getLogger(DefaultSelfEvolveService.class);

    @Inject
    SelfEvolveConfig config;

    @Inject
    @Any
    Instance<EvolutionEventStorePort> storeInstances;

    @Inject
    @Any
    Instance<ExtractedMemoryStorePort> extractedStoreInstances;

    @Inject
    @Any
    Instance<DirectiveStorePort> directiveStoreInstances;

    @Inject
    Instance<MemoryExtractorLite> extractorInstance;

    private EvolutionEventStorePort store;
    private ExtractedMemoryStorePort extractedStore;
    private DirectiveStorePort directiveStore;
    private FlushTriggerPolicy flushPolicy;
    private final Map<String, ConversationBuffer> buffers = new ConcurrentHashMap<>();
    private final Map<String, Long> lastEventTimestamps = new ConcurrentHashMap<>();
    private ScheduledExecutorService idleTimer;

    @PostConstruct
    void init() {
        if (!config.enabled()) {
            store = new NoOpEvolutionEventStore();
            log.info("[SelfEvolve] Disabled — all operations are no-ops");
            return;
        }
        // Resolve the real store from CDI (e.g. SqliteEvolutionEventStore)
        store = resolveStore();
        extractedStore = resolveExtractedStore();
        directiveStore = resolveDirectiveStore();
        flushPolicy = new FlushTriggerPolicy(
                config.flush().maxEvents(), config.flush().idleMs());
        idleTimer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("self-evolve-idle").daemon(true).factory());
        long checkIntervalMs = Math.max(config.flush().idleMs() / 2, 10_000);
        idleTimer.scheduleAtFixedRate(this::checkIdleFlush, checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
        log.infof(
                "[SelfEvolve] Enabled — buffer=%d, idle=%dms, store=%s",
                config.bufferSize(), config.flush().idleMs(), store.getClass().getSimpleName());
    }

    private EvolutionEventStorePort resolveStore() {
        for (EvolutionEventStorePort candidate : storeInstances) {
            if (!(candidate instanceof NoOpEvolutionEventStore)) {
                log.infof(
                        "[SelfEvolve] Resolved store: %s", candidate.getClass().getSimpleName());
                return candidate;
            }
        }
        log.warn("[SelfEvolve] No real store found — falling back to NoOp");
        return new NoOpEvolutionEventStore();
    }

    private ExtractedMemoryStorePort resolveExtractedStore() {
        for (ExtractedMemoryStorePort candidate : extractedStoreInstances) {
            if (!(candidate instanceof NoOpExtractedMemoryStore)) {
                log.infof(
                        "[SelfEvolve] Resolved extracted memory store: %s",
                        candidate.getClass().getSimpleName());
                return candidate;
            }
        }
        log.warn("[SelfEvolve] No real extracted memory store found — falling back to NoOp");
        return new NoOpExtractedMemoryStore();
    }

    private DirectiveStorePort resolveDirectiveStore() {
        for (DirectiveStorePort candidate : directiveStoreInstances) {
            if (!(candidate instanceof NoOpDirectiveStore)) {
                log.infof(
                        "[SelfEvolve] Resolved directive store: %s",
                        candidate.getClass().getSimpleName());
                return candidate;
            }
        }
        log.warn("[SelfEvolve] No real directive store found — falling back to NoOp");
        return new NoOpDirectiveStore();
    }

    @PreDestroy
    void shutdown() {
        if (idleTimer != null) {
            idleTimer.shutdown();
            try {
                if (!idleTimer.awaitTermination(5, TimeUnit.SECONDS)) {
                    idleTimer.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                idleTimer.shutdownNow();
            }
        }
        // Final flush of all remaining buffers
        if (config != null && config.enabled()) {
            for (Map.Entry<String, ConversationBuffer> entry : buffers.entrySet()) {
                flushBuffer(entry.getValue(), FlushReason.SESSION_END);
            }
        }
    }

    /**
     * Appends an event to the session buffer. Creates buffer if absent.
     * If buffer reaches flush threshold, flushes to store immediately.
     *
     * @return true if flush was triggered
     */
    public boolean append(EvolutionEvent event) {
        if (!config.enabled()) {
            return false;
        }
        ConversationBuffer buffer = buffers.computeIfAbsent(
                event.sessionId(), sid -> new InMemoryConversationBuffer(sid, config.bufferSize()));
        lastEventTimestamps.put(event.sessionId(), System.currentTimeMillis());

        boolean shouldFlush = buffer.append(event);
        if (shouldFlush) {
            flushBuffer(buffer, FlushReason.EVENT_THRESHOLD);
            return true;
        }
        return false;
    }

    /**
     * Convenience method to create and append an event.
     */
    public boolean emit(
            String sessionId,
            String userId,
            EvolutionEventType type,
            String role,
            String content,
            String toolName,
            Integer turnNumber) {
        if (!config.enabled()) {
            return false;
        }
        EvolutionEvent event =
                EvolutionEvent.create(sessionId, userId, type, role, content, toolName, turnNumber, null);
        return append(event);
    }

    /**
     * Flushes the buffer for a session. Called on session end.
     * After flushing, runs rule-based extraction (Phase 9) on the flushed events.
     */
    public void flushSession(String sessionId) {
        if (!config.enabled()) {
            return;
        }
        ConversationBuffer buffer = buffers.remove(sessionId);
        lastEventTimestamps.remove(sessionId);
        if (buffer != null) {
            List<EvolutionEvent> events = buffer.drain(FlushReason.SESSION_END);
            if (!events.isEmpty()) {
                // Persist events to store
                persistEvents(events, FlushReason.SESSION_END);
                // Run rule-based extraction (Phase 9)
                runExtraction(events);
            }
        }
    }

    /**
     * Returns the current buffer size for a session (0 if no buffer).
     */
    public int bufferSize(String sessionId) {
        ConversationBuffer buffer = buffers.get(sessionId);
        return buffer != null ? buffer.size() : 0;
    }

    /**
     * Returns true if self-evolve is enabled.
     */
    public boolean isEnabled() {
        return config != null && config.enabled();
    }

    /**
     * Returns the directive store. Used by policy components.
     */
    public DirectiveStorePort directiveStore() {
        return directiveStore != null ? directiveStore : new NoOpDirectiveStore();
    }

    private void flushBuffer(ConversationBuffer buffer, FlushReason reason) {
        List<EvolutionEvent> events = buffer.drain(reason);
        if (events.isEmpty()) {
            return;
        }
        persistEvents(events, reason);
    }

    private void persistEvents(List<EvolutionEvent> events, FlushReason reason) {
        try {
            int persisted = store.insertBatch(events);
            log.debugf(
                    "[SelfEvolve] Flushed %s events (reason=%s, persisted=%s)",
                    Integer.valueOf(events.size()), reason, Integer.valueOf(persisted));
        } catch (Exception e) {
            log.errorf(
                    "[SelfEvolve] Flush failed (reason=%s, events=%s): %s",
                    reason, events.size(), LogUtil.rootMessage(e));
        }
    }

    /**
     * Runs rule-based extraction (Phase 9) on a session's events.
     * Extracted memories are persisted to the extracted memory store.
     */
    private void runExtraction(List<EvolutionEvent> events) {
        if (events.isEmpty() || extractorInstance.isUnsatisfied()) {
            return;
        }
        try {
            MemoryExtractorLite extractor = extractorInstance.get();
            String userId = events.getFirst().userId();
            List<ExtractedMemory> memories = extractor.extract(events, userId);
            if (!memories.isEmpty()) {
                int persisted = extractedStore.insertBatch(memories);
                log.debugf(
                        "[SelfEvolve] Extracted and persisted %d/%d memories",
                        Integer.valueOf(persisted), Integer.valueOf(memories.size()));
            }
        } catch (Exception e) {
            log.warnf("[SelfEvolve] Extraction failed: %s — %s", e.getClass().getSimpleName(), LogUtil.rootMessage(e));
            log.debugf("[SelfEvolve] Extraction stack:\n%s", LogUtil.trimmedStack(e, 15));
        }
    }

    private void checkIdleFlush() {
        if (!config.enabled() || flushPolicy == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ConversationBuffer> entry : buffers.entrySet()) {
            String sessionId = entry.getKey();
            ConversationBuffer buffer = entry.getValue();
            Long lastTs = lastEventTimestamps.get(sessionId);
            if (lastTs != null && flushPolicy.shouldFlushOnIdle(lastTs, now, buffer.hasEvents())) {
                log.debugf("[SelfEvolve] Idle flush triggered for session %s", sessionId);
                flushBuffer(buffer, FlushReason.IDLE_TIMEOUT);
            }
        }
    }
}
