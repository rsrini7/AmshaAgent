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

    private static final int AWAIT_TIMEOUT_SECONDS = 15;

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
     */
    public void registerLatch(String sessionId) {
        inFlightLatches.put(sessionId, new CountDownLatch(1));
        log.debugf("Compaction bridge registered in-flight latch for session %s", sessionId);
    }

    /**
     * Blocks the calling thread until any in-flight compaction for the given
     * session completes, then returns immediately.
     * <p>
     * Fast-path: if no latch is registered (no compaction in progress), returns
     * instantly with no locking overhead.
     */
    public void awaitIfInFlight(String sessionId) {
        CountDownLatch latch = inFlightLatches.get(sessionId);
        if (latch == null) return;

        try {
            boolean completed = latch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                log.warnf("CompactionEventBridge: timed out waiting for compaction " +
                          "of session %s — proceeding with potentially stale state", sessionId);
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
            log.errorf(e, "Compaction failed for session %s: %s",
                    request.sessionId(), e.getMessage());
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