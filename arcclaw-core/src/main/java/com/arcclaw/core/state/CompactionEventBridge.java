package com.arcclaw.core.state;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import com.arcclaw.core.compaction.CompactionInFlightFlag;
import com.arcclaw.core.compaction.CompactionRequest;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;

/**
 * Bridges the asynchronous {@link CompactionRequest} event to the {@link CompactionService}.
 * <p>
 * Also owns the per-session CountDownLatch map used by PersistentChatMemoryStore
 * to block getMessages() reads until any in-flight compaction for that session
 * has fully committed - preventing the TOCTOU race where the LLM prompt builder
 * reads a thin post-compaction context mid-turn and produces "Done." in 1 token.
 * <p>
 * Self-deadlock prevention: the compaction thread itself must NOT block on its own
 * latch. awaitIfInFlight() is a no-op when called from the thread currently
 * performing compaction for that session. Without this guard, the compaction thread
 * calls getMessages() internally, hits the gate it registered, and waits for itself.
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

    /**
     * Maps sessionId → the ID of the thread currently performing compaction for it.
     * Set at the start of onCompactionRequested and cleared in finally.
     * Used by awaitIfInFlight() to detect and skip self-deadlock.
     */
    private final ConcurrentHashMap<String, Long> compactionThreadIds =
            new ConcurrentHashMap<>();

    @Inject
    CompactionService compactionService;

    @Inject
    CompactionInFlightFlag compactionInFlight;

    @Inject
    StatefulAgentStore agentStore;

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
            log.warnf("CompactionEventBridge: overwrote existing latch for session %s - " +
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
     * Fast-paths:
     * <ol>
     *   <li>No latch registered - no compaction in progress, returns instantly.</li>
     *   <li>Caller IS the compaction thread for this session - returns instantly to
     *       prevent self-deadlock (compaction reads chat history via getMessages()
     *       which would otherwise block on its own latch indefinitely).</li>
     * </ol>
     * On timeout, the stale map entry is removed so future calls fast-path
     * correctly instead of re-entering the timeout loop.
     */
    public void awaitIfInFlight(String sessionId) {
        // Self-deadlock guard: the compaction thread must never wait on its own latch.
        // Without this, compactionService.compact() calls getMessages() internally,
        // which calls awaitIfInFlight(), which blocks on a latch the caller owns -
        // resulting in a 30-second timeout stall on every compaction that reads history.
        Long ownerThreadId = compactionThreadIds.get(sessionId);
        if (ownerThreadId != null && ownerThreadId == Thread.currentThread().threadId()) {
            log.tracef("CompactionEventBridge: skipping self-wait for compaction thread " +
                       "on session %s", sessionId);
            return;
        }

        CountDownLatch latch = inFlightLatches.get(sessionId);
        if (latch == null) return;

        try {
            boolean completed = latch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                log.warnf("CompactionEventBridge: timed out waiting for compaction " +
                          "of session %s - proceeding with potentially stale state", sessionId);
                // Remove the stale entry so future calls for this session fast-path
                // correctly. Two-arg remove: only removes if value still matches
                // (safe against a concurrent registerLatch replacing it with a fresh one).
                inFlightLatches.remove(sessionId, latch);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warnf("CompactionEventBridge: interrupted while awaiting compaction " +
                      "for session %s", sessionId);
        }
    }

    public void onCompactionRequested(@ObservesAsync CompactionRequest request) {
        // Register this thread as the compaction owner BEFORE calling compact(),
        // so any getMessages() calls inside compact() skip awaitIfInFlight().
        compactionThreadIds.put(request.sessionId(), Thread.currentThread().threadId());
        try {
            log.debugf("Compaction bridge received request for session %s", request.sessionId());

            // F15: Skip compaction only if the session is actively running (IN_PROGRESS),
            // to avoid racing with LLM chat memory reads. Safe to compact when paused
            // for human approval (AWAITING_APPROVAL) — no LLM turn is in flight.
            if (agentStore.findLatestAwaitingApproval(request.sessionId())
                    .map(c -> c.getStatus() == CheckpointStatus.IN_PROGRESS)
                    .orElse(false)) {
                log.infof("Compaction deferred for session %s: session actively in progress", request.sessionId());
                return;
            }

            compactionService.compact(request.sessionId(), request.userId());
        } catch (Exception e) {
            log.errorf("Compaction failed for session %s: %s",
                    request.sessionId(), ExceptionUtils.getRootCauseMessage(e));
            if (log.isTraceEnabled()) {
                log.tracef("Full compaction failure detail for session %s: %s",
                        request.sessionId(), ExceptionUtils.getStackTrace(e));
            }
        } finally {
            // Clear the thread ownership record first, then release the latch.
            // Order matters: clearing first means any awaitIfInFlight() call that
            // arrives after this point will go through the normal latch path (which
            // will immediately return because the latch is removed next).
            compactionThreadIds.remove(request.sessionId());

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