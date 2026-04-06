package com.arcclaw.core.agent.loop;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import io.quarkus.scheduler.Scheduled;
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

    /**
     * Polls every 30 seconds for rate-limited sessions that are ready to resume.
     */
    @Scheduled(every = "30s", identity = "rate-limit-retry")
    public void processPendingRetries() {
        List<AgentCheckpoint> awaiting = agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY);
        if (awaiting.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();

        for (AgentCheckpoint cp : awaiting) {
            if (cp.getUpdatedAt() == null) continue;

            long retryAfterMs = cp.getRetryAfterMs() != null ? cp.getRetryAfterMs() : 30000;
            LocalDateTime readyAt = cp.getUpdatedAt().plusNanos(retryAfterMs * 1_000_000L);

            if (now.isAfter(readyAt)) {
                log.infof("Resuming rate-limited session %s (retryAfterMs=%d)",
                        cp.getSessionId(), retryAfterMs);

                // Clear retry metadata before resume attempt
                cp.setRetryAfterMs(null);
                agentStore.save(cp);

                try {
                    // Offload to background thread via AgentLoop.resume
                    // AgentLoop.run() will transition status to IN_PROGRESS when it starts.
                    agentLoop.resume(cp.getSessionId(), cp.getAgentRole(), cp.getOriginalGoal());
                } catch (Exception e) {
                    log.errorf(e, "Failed to resume rate-limited session %s", cp.getSessionId());
                    agentStore.markFailed(cp.getId(), "Background retry failed: " + e.getMessage());
                }
            }
        }
    }
}
