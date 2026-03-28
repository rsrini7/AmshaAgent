package com.arcclaw.core.state;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import com.arcclaw.core.compaction.CompactionInFlightFlag;
import com.arcclaw.core.compaction.CompactionRequest;
import org.jboss.logging.Logger;

/**
 * Bridges the asynchronous {@link CompactionRequest} event to the {@link CompactionService}.
 * <p>
 * Also owns the per-session CountDownLatch map used by PersistentChatMemoryStore
 * to block getMessages() reads until any in-flight compaction for that session
 * has fully committed — preventing the TOCTOU race where the LLM prompt builder
 * reads a thin post-compaction context mid-turn and produces "Done." in 1 token.
 */
@ApplicationScoped
public class CompactionEventBridge {

    private static final Logger log = Logger.getLogger(CompactionEventBridge.class);

    private static final int AWAIT_TIMEOUT_SECONDS = 30;

    /**
     * Per-session latches. Registered synchronously by CompactionHandler BEFORE
     * the async event fires, so any concurrent getMessages() call on the same
     * session will always see the latch if compaction is in progress.
     */
    private final ConcurrentHashMap<String, CountDownLatch> inFlightLatches =
            new ConcurrentHashMap<>();

    @Inject
    CompactionService compactionService;

    @Inject
    CompactionInFlightFlag compactionInFlight;

    /**
     * Called synchronously by CompactionHandler (order=60) before firing the
     * async event. Guarantees the latch is in the map before AgentDispatchHandler
     * (order=70) runs and before getMessages() is called during prompt building.
     * <p>
     * If a latch already exists for this session (duplicate registration), the old
     * latch is counted down immediately to release any blocked waiters before being
     * replaced. This prevents threads from being stranded on an orphaned latch.
     */
    public void registerLatch(String sessionId) {
        CountDownLatch prev = inFlightLatches.put(sessionId, new CountDownLatch(1));
        if (prev != null) {
            // Duplicate registration — release any threads blocked on the old latch
            // before replacing it, so they are not stranded for AWAIT_TIMEOUT_SECONDS.
            log.warnf("CompactionEventBridge: overwrote existing latch for session %s — " +
                      "possible duplicate compaction registration; releasing old waiters",
                      sessionId);
            prev.countDown();
        }
        log.debugf("Compaction bridge registered in-flight latch for session %s", sessionId);
    }

    /**
     * Blocks the calling thread until any in-flight compaction for the given
     * session completes, then returns immediately.
     * <p>
     * Fast-path: if no latch is registered (no compaction in progress), returns
     * instantly with no locking overhead.
     * <p>
     * On timeout, the stale map entry is removed so future calls fast-path
     * correctly instead of re-entering the timeout loop.
     */
    public void awaitIfInFlight(String sessionId) {
        CountDownLatch latch = inFlightLatches.get(sessionId);
        if (latch == null) return;

        try {
            boolean completed = latch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                log.warnf("CompactionEventBridge: timed out waiting for compaction " +
                          "of session %s — proceeding with potentially stale state", sessionId);
                // Remove the stale entry so future calls for this session fast-path
                // correctly. Use remove(key, value) to avoid removing a newer latch
                // that may have been registered by a concurrent registerLatch() call.
                inFlightLatches.remove(sessionId, latch);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warnf("CompactionEventBridge: interrupted while awaiting compaction " +
                      "for session %s", sessionId);
        }
    }

    public void onCompactionRequested(@ObservesAsync CompactionRequest request) {
        try {
            log.debugf("Compaction bridge received request for session %s", request.sessionId());
            compactionService.compact(request.sessionId(), request.userId());
        } catch (Exception e) {
            // e.getMessage() can be null for exceptions that override getMessage()
            // to return null or are constructed with no message (e.g. new RuntimeException()).
            log.errorf(e, "Compaction failed for session %s: %s",
                    request.sessionId(),
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            // Release the store read-gate BEFORE clearing the boolean flag,
            // so awaitIfInFlight() unblocks as soon as the DB write is done.
            CountDownLatch latch = inFlightLatches.remove(request.sessionId());
            if (latch != null) latch.countDown();
            log.debugf("Compaction bridge released in-flight latch for session %s",
                    request.sessionId());

            compactionInFlight.set(request.sessionId(), false);
            log.debugf("Compaction bridge released in-flight lock for session %s",
                    request.sessionId());
        }
    }
}