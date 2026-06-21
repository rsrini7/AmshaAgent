package com.amsha.core.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.observability.AgentObserver;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Observes AgentLoop execution and records audit events upon completion.
 * Computes the total cost of the loop execution for the audit record.
 */
@ApplicationScoped
public class AuditObserver implements AgentObserver {

    @Inject
    AuditService auditService;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @ConfigProperty(name = "amsha.governance.price-per-1k-input", defaultValue = "0.003")
    double pricePer1kInput;

    @ConfigProperty(name = "amsha.governance.price-per-1k-output", defaultValue = "0.015")
    double pricePer1kOutput;

    // Track tokens per session since recordLlmCall fires multiple times per loop
    private final ConcurrentHashMap<String, TokenTracker> trackers = new ConcurrentHashMap<>();

    @Override
    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
        if (sessionId == null) return;
        TokenTracker tracker = trackers.computeIfAbsent(sessionId, k -> new TokenTracker());
        tracker.input.addAndGet(inputTokens);
        tracker.output.addAndGet(outputTokens);
    }

    @Override
    public void onLoopComplete(String sessionId, String userId, String agentRole, String goalHash, AgentResult result) {
        if (sessionId == null || result == null) return;

        TokenTracker tracker = trackers.remove(sessionId);
        int inTokens = tracker != null ? tracker.input.get() : 0;
        int outTokens = tracker != null ? tracker.output.get() : 0;

        heartbeatTracker.recordTokens(sessionId, inTokens, outTokens);

        double costDouble = (inTokens / 1000.0) * pricePer1kInput + (outTokens / 1000.0) * pricePer1kOutput;
        BigDecimal cost = BigDecimal.valueOf(costDouble);

        auditService.recordLoopCompletion(sessionId, userId, agentRole, goalHash, result, inTokens, outTokens, cost);
    }

    public void cleanupSession(String sessionId) {
        if (sessionId != null) {
            trackers.remove(sessionId);
        }
    }

    private static final class TokenTracker {
        final AtomicInteger input = new AtomicInteger();
        final AtomicInteger output = new AtomicInteger();
    }
}
