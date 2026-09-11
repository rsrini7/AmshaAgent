package com.amsha.core.observability;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.state.ActivityPhase;
import com.amsha.core.state.StatefulAgentStore;

import org.jboss.logging.Logger;

/**
 * Keeps the JVM-local resume mark accurate while a turn is actually running.
 *
 * <p>Every active reasoning step begins with an immediate refresh and schedules a
 * periodic heartbeat so that long-running provider calls or tool executions do not
 * expire the resume mark before completion. Every completed reasoning step cancels
 * the heartbeat, refreshes the mark's activity timestamp, and updates its coarse phase
 * label so that:
 * <ul>
 *   <li>the mark no longer expires after a fixed 60s window just because a single
 *       step is slow - it only expires after <em>no progress</em> for the TTL, and</li>
 *   <li>the busy message and {@code /status} can show what the agent is doing
 *       (e.g. {@code TOOL:read_file}, {@code OBSERVING}) instead of a bare
 *       "processing another request".</li>
 * </ul>
 *
 * <p>Discovered automatically by {@link com.amsha.core.agent.loop.ObservabilityManager}
 * through its {@code Instance<AgentObserver>} injection; no registration wiring required.
 */
@ApplicationScoped
public class ResumeActivityObserver implements AgentObserver {

    private static final Logger log = Logger.getLogger(ResumeActivityObserver.class);
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15;

    private final ScheduledExecutorService heartbeatScheduler;
    private final Map<String, ScheduledFuture<?>> activeHeartbeats = new ConcurrentHashMap<>();
    private final Set<String> activeBatches = ConcurrentHashMap.newKeySet();
    private final StatefulAgentStore agentStore;

    @Inject
    public ResumeActivityObserver(StatefulAgentStore agentStore) {
        this(agentStore, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "resume-activity-heartbeat");
            t.setDaemon(true);
            return t;
        }));
    }

    ResumeActivityObserver(StatefulAgentStore agentStore, ScheduledExecutorService heartbeatScheduler) {
        this.agentStore = agentStore;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @Override
    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
        // No-op: LLM token accounting is handled by other observers.
    }

    @Override
    public void onBatchStart(String sessionId, String role, int batchSize) {
        if (sessionId != null && batchSize > 1) {
            activeBatches.add(sessionId);
        }
    }

    @Override
    public void onBatchComplete(String sessionId, String role) {
        if (sessionId != null) {
            activeBatches.remove(sessionId);
            stopHeartbeat(sessionId);
        }
    }

    @Override
    public void onStepStart(String sessionId, String role) {
        if (sessionId == null) {
            return;
        }
        agentStore.refreshResuming(sessionId, null);
        activeHeartbeats.compute(sessionId, (_, existing) -> {
            if (existing != null) {
                existing.cancel(false);
            }
            return heartbeatScheduler.scheduleWithFixedDelay(
                    () -> {
                        try {
                            agentStore.refreshResuming(sessionId, null);
                        } catch (Exception e) {
                            log.debugf(
                                    "Heartbeat refresh failed for session %s: %s", sessionId, LogUtil.rootMessage(e));
                        }
                    },
                    HEARTBEAT_INTERVAL_SECONDS,
                    HEARTBEAT_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
        });
    }

    @Override
    public void onStepComplete(String sessionId, String role, ReasoningStep step) {
        if (sessionId == null) {
            return;
        }
        if (!activeBatches.contains(sessionId)) {
            stopHeartbeat(sessionId);
        }
        agentStore.refreshResuming(sessionId, ActivityPhase.forStep(step));
    }

    @Override
    public void onLoopComplete(String sessionId, String userId, String agentRole, String goalHash, AgentResult result) {
        if (sessionId != null) {
            activeBatches.remove(sessionId);
            stopHeartbeat(sessionId);
        }
    }

    private void stopHeartbeat(String sessionId) {
        ScheduledFuture<?> future = activeHeartbeats.remove(sessionId);
        if (future != null) {
            future.cancel(false);
        }
    }

    @PreDestroy
    void shutdown() {
        activeBatches.clear();
        activeHeartbeats.values().forEach(f -> f.cancel(false));
        activeHeartbeats.clear();
        heartbeatScheduler.shutdown();
    }
}
