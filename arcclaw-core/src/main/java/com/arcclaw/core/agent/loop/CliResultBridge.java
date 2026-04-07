package com.arcclaw.core.agent.loop;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.result.AgentResult;
import org.jboss.logging.Logger;

/**
 * Shared bridge to pass agent results from an asynchronous resume (e.g. REST)
 * back to a blocking CLI session.
 */
@ApplicationScoped
public class CliResultBridge {
    private static final Logger log = Logger.getLogger(CliResultBridge.class);
    
    private final ConcurrentHashMap<String, CompletableFuture<AgentResult>> waiters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AgentResult> bufferedResults = new ConcurrentHashMap<>();

    /**
     * Registers interest in a session result. If a result arrived while no one was waiting, 
     * it is returned as an already-completed future.
     */
    public CompletableFuture<AgentResult> register(String sessionId) {
        log.debugf("CliResultBridge: registering interest for session %s", sessionId);
        
        AgentResult buffered = bufferedResults.remove(sessionId);
        if (buffered != null) {
            log.infof("CliResultBridge: delivering buffered result for session %s immediately", sessionId);
            return CompletableFuture.completedFuture(buffered);
        }

        return waiters.computeIfAbsent(sessionId, k -> new CompletableFuture<>());
    }

    /** Clears any waiters or buffered results for an abandoned session. */
    public void cancel(String sessionId) {
        waiters.remove(sessionId);
        bufferedResults.remove(sessionId);
    }

    /**
     * Signals result completion. If a CLI waiter is active, it receives the result now.
     * Otherwise, the result is buffered for later retrieval by a late-arriving register() call.
     */
    public void complete(String sessionId, AgentResult result) {
        CompletableFuture<AgentResult> waiter = waiters.remove(sessionId);
        if (waiter != null) {
            log.infof("CliResultBridge: signaling active waiter for session %s", sessionId);
            waiter.complete(result);
        } else {
            // No one waiting yet? Buffer it (e.g. REST approved before user typed /approve)
            log.infof("CliResultBridge: no active waiter for session %s — buffering result", sessionId);
            bufferedResults.put(sessionId, result);
        }
    }

    /**
     * Blocking wait for a result. MUST call register() correctly or this will wait on a fresh future.
     */
    public AgentResult waitForResult(String sessionId, long timeoutMs) {
        // computeIfAbsent ensures we are always waiting on a future
        CompletableFuture<AgentResult> future = waiters.computeIfAbsent(sessionId, k -> {
            AgentResult buffered = bufferedResults.remove(sessionId);
            if (buffered != null) return CompletableFuture.completedFuture(buffered);
            return new CompletableFuture<>();
        });

        try {
            log.debugf("CliResultBridge: waiting %d ms for session %s", timeoutMs, sessionId);
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debugf("CliResultBridge: timeout or interruption waiting for session %s", sessionId);
            return null;
        } finally {
            waiters.remove(sessionId);
        }
    }
}
