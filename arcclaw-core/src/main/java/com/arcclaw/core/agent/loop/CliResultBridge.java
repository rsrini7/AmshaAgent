package com.arcclaw.core.agent.loop;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.result.AgentResult;
import org.jboss.logging.Logger;

/**
 * Shared bridge to pass agent results from an asynchronous resume (e.g. REST or Telegram approval)
 * back to a blocking CLI session.
 *
 * <p>Race condition handled: if the REST approval completes the task before the CLI thread
 * calls register() or waitForResult(), the result is buffered and delivered immediately
 * when the CLI thread eventually arrives.
 *
 * <p>Calling convention: always use waitForResult() — it internally delegates to register()
 * as the single source of truth for waiter/buffer resolution.
 */
@ApplicationScoped
public class CliResultBridge {

    private static final Logger log = Logger.getLogger(CliResultBridge.class);

    /** Active waiters: CLI threads blocked on a CompletableFuture. */
    private final ConcurrentHashMap<String, CompletableFuture<AgentResult>> waiters =
            new ConcurrentHashMap<>();

    /**
     * Buffered results: task completed before CLI thread arrived.
     * Entries are evicted on retrieval or by SessionReaper GC sweep via cancel().
     */
    private final ConcurrentHashMap<String, AgentResult> bufferedResults =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Registers interest in a session result.
     *
     * <p>If the result already arrived (buffered), returns an immediately completed future.
     * If no result yet, parks a CompletableFuture for complete() to resolve later.
     *
     * <p>This is the single source of truth — waitForResult() delegates here.
     */
    public CompletableFuture<AgentResult> register(String sessionId) {
        log.debugf("CliResultBridge: registering interest for session %s", sessionId);

        AgentResult buffered = bufferedResults.remove(sessionId);
        if (buffered != null) {
            log.infof("CliResultBridge: delivering buffered result for session %s immediately", sessionId);
            return CompletableFuture.completedFuture(buffered);
        }

        // computeIfAbsent is safe here — no side-effecting lambdas, pure construction
        return waiters.computeIfAbsent(sessionId, k -> new CompletableFuture<>());
    }

    /**
     * Signals result completion.
     *
     * <p>If a CLI waiter is active, it receives the result immediately.
     * Otherwise, the result is buffered for a late-arriving register() or waitForResult() call.
     */
    public void complete(String sessionId, AgentResult result) {
        CompletableFuture<AgentResult> waiter = waiters.remove(sessionId);
        if (waiter != null) {
            log.infof("CliResultBridge: signaling active waiter for session %s", sessionId);
            waiter.complete(result);
        } else {
            log.infof("CliResultBridge: no active waiter for session %s — buffering result", sessionId);
            bufferedResults.put(sessionId, result);
        }
    }

    /**
     * Blocking wait for a result. Delegates to register() — the single resolution path.
     *
     * <p>Do NOT call register() before this — it internally handles both the
     * "result already arrived" and "result pending" cases.
     *
     * @param sessionId  the session to wait on
     * @param timeoutMs  max wait in milliseconds
     * @return the AgentResult, or null on timeout / interruption
     */
    public AgentResult waitForResult(String sessionId, long timeoutMs) {
        CompletableFuture<AgentResult> future = register(sessionId);
        try {
            log.debugf("CliResultBridge: waiting %d ms for session %s", timeoutMs, sessionId);
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debugf("CliResultBridge: timeout or interruption waiting for session %s", sessionId);
            return null;
        } finally {
            // Always clean up the waiter slot, even if already removed by complete()
            waiters.remove(sessionId);
        }
    }

    /**
     * Cancels and cleans up any state for an abandoned or expired session.
     *
     * <p>Called by SessionReaper during GC sweep to prevent memory leaks
     * from sessions that were approved remotely but never picked up by a CLI thread.
     *
     * <pre>
     *   // In SessionReaper GC sweep:
     *   cliResultBridge.cancel(expiredSessionId);
     * </pre>
     */
    public void cancel(String sessionId) {
        CompletableFuture<AgentResult> waiter = waiters.remove(sessionId);
        if (waiter != null) {
            log.debugf("CliResultBridge: cancelling active waiter for session %s", sessionId);
            waiter.cancel(true);
        }
        AgentResult dropped = bufferedResults.remove(sessionId);
        if (dropped != null) {
            log.debugf("CliResultBridge: evicting buffered result for expired session %s", sessionId);
        }
    }
}
