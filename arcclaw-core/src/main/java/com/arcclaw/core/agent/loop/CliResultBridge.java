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
    private final ConcurrentHashMap<String, CompletableFuture<AgentResult>> pending = new ConcurrentHashMap<>();
 
    public void register(String sessionId) {
        log.debugf("CliResultBridge: registering interest for session %s", sessionId);
        pending.computeIfAbsent(sessionId, k -> new CompletableFuture<>());
    }

    public void cancel(String sessionId) {
        pending.remove(sessionId);
    }

    public void complete(String sessionId, AgentResult result) {
        CompletableFuture<AgentResult> future = pending.get(sessionId);
        if (future != null) {
            log.infof("CliResultBridge: signaling result for session %s", sessionId);
            future.complete(result);
        } else {
            log.debugf("CliResultBridge: no waiter for session %s (likely not a CLI-blocked session)", sessionId);
        }
    }

    public AgentResult waitForResult(String sessionId, long timeoutMs) {
        CompletableFuture<AgentResult> future = pending.get(sessionId);
        if (future == null) {
            log.warnf("CliResultBridge: attempt to wait for unregistered session %s", sessionId);
            return null;
        }
        try {
            log.debugf("CliResultBridge: waiting %d ms for session %s", timeoutMs, sessionId);
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debugf("CliResultBridge: timeout or interruption waiting for session %s", sessionId);
            return null;
        } finally {
            pending.remove(sessionId);
        }
    }
}
