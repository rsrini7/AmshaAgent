package com.arcclaw.core.agent.loop;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Background scheduler that polls for sessions in AWAITING_RETRY state
 * and resumes them once their backoff duration has elapsed. §7.36
 */
@ApplicationScoped
public class RateLimitRetryScheduler {

    private static final Logger log = Logger.getLogger(RateLimitRetryScheduler.class);

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    AgentLoop agentLoop;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "arcclaw.retry.default-delay-ms", defaultValue = "30000")
    long defaultRetryDelayMs;

    private final AtomicLong backlogMaxAge = new AtomicLong(0);

    @PostConstruct
    void init() {
        meterRegistry.gauge("arcclaw.retry.backlog.max_age_seconds", backlogMaxAge);
    }

    /**
     * Polls every 30 seconds for rate-limited sessions that are ready to resume.
     * 
     * <p><strong>§5.9 (MUST):</strong> This method is transaction-free. Atomic state 
     * transition is handled by {@link StatefulAgentStore#claimForRetry(Long)}.
     */
    @Scheduled(every = "30s", identity = "rate-limit-retry")
    @ActivateRequestContext
    public void processPendingRetries() {
        List<AgentCheckpoint> awaiting = agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY);
        log.tracef("RateLimitRetryScheduler: scanning for ready sessions (found %d in AWAITING_RETRY)", awaiting.size());
        
        if (awaiting.isEmpty()) {
            backlogMaxAge.set(0);
            return;
        }

        Instant now = Instant.now();
        long maxAgeSec = 0;

        for (AgentCheckpoint cp : awaiting) {
            if (cp.getUpdatedAt() == null) continue;

            long ageSec = Duration.between(cp.getUpdatedAt(), now).getSeconds();
            maxAgeSec = Math.max(maxAgeSec, ageSec);

            long retryAfterMs = cp.getRetryAfterMs() != null ? cp.getRetryAfterMs() : defaultRetryDelayMs;
            Instant readyAt = cp.getUpdatedAt().plus(retryAfterMs, ChronoUnit.MILLIS);

            if (now.isAfter(readyAt)) {
                log.infof("Resuming rate-limited session %s (retryAfterMs=%d, age=%ds)",
                        cp.getSessionId(), retryAfterMs, ageSec);

                // 🟢 ATOMIC CLAIM: Transition to IN_PROGRESS in a fresh transaction inside the adapter
                if (agentStore.claimForRetry(cp.getId())) {
                    try {
                        // Offload to background thread via AgentLoop.resume
                        agentLoop.resume(cp.getSessionId(), cp.getAgentRole(), cp.getOriginalGoal());
                    } catch (Exception e) {
                        log.errorf(e, "Failed to resume rate-limited session %s", cp.getSessionId());
                        agentStore.markFailed(cp.getId(), "Background retry failed: " + e.getMessage());
                    }
                } else {
                    log.debugf("Session %s was already claimed by another thread, skipping", cp.getSessionId());
                }
            } else {
                long remaining = Duration.between(now, readyAt).toSeconds();
                log.debugf("Session %s still in backoff (%ds remaining)", cp.getSessionId(), remaining);
            }
        }
        
        backlogMaxAge.set(maxAgeSec);
    }

    /**
     * Sweeps for checkpoints stuck in AWAITING_RETRY for > 30 minutes.
     * This handles cases where the scheduler might have missed an event or crashed.
     */
    @Scheduled(every = "5m", identity = "rate-limit-orphan-sweeper")
    public void sweepOrphanedRetries() {
        List<AgentCheckpoint> awaiting = agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY);
        Instant cutoff = Instant.now().minus(30, ChronoUnit.MINUTES);

        for (AgentCheckpoint cp : awaiting) {
            if (cp.getUpdatedAt() != null && cp.getUpdatedAt().isBefore(cutoff)) {
                log.errorf("Checkpoint %d is STALE in AWAITING_RETRY (updatedAt=%s). Marking as ORPHANED.",
                        cp.getId(), cp.getUpdatedAt());
                
                agentStore.markOrphaned(cp.getId(), 
                    "Abandoned after 30 minutes in AWAITING_RETRY (potential scheduler outage)");
                
                meterRegistry.counter("arcclaw.retry.orphaned.count").increment();
            }
        }
    }
}
