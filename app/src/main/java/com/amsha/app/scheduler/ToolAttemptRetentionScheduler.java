package com.amsha.app.scheduler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import com.amsha.common.util.LogUtil;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.persistence.api.ToolAttemptStorePort;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;

/**
 * Scheduled job for tool attempt execution journal retention and pruning per Proposal §12.4.
 * <p>
 * Prunes terminal attempts, locks, resolutions, and delivered outbox records older than
 * {@code amsha.tools.attempt.retention} (default 30 days) across all known workspaces.
 * Retains {@code CONSUMED} claims as replay tombstones for the lifetime of each session;
 * claims are deleted only upon permanent session deletion via {@link ToolAttemptStorePort#deleteClaimsBySession}.
 */
@ApplicationScoped
public class ToolAttemptRetentionScheduler {

    private static final Logger log = Logger.getLogger(ToolAttemptRetentionScheduler.class);

    @Inject
    Instance<ToolAttemptStorePort> storePortInstance;

    @ConfigProperty(name = "amsha.tools.attempt.retention", defaultValue = "30d")
    Duration retentionDuration;

    @ConfigProperty(name = "amsha.tools.attempt.retention-enabled", defaultValue = "true")
    boolean retentionEnabled;

    /**
     * Daily sweep to prune terminal attempts older than the configured retention period.
     */
    @Scheduled(cron = "{amsha.tools.attempt.retention-cron:0 0 3 * * ?}")
    void runDailyPrune() {
        if (!retentionEnabled) {
            log.debug("[ToolAttemptRetention] Prune sweep skipped — retention disabled");
            return;
        }
        if (storePortInstance.isUnsatisfied()) {
            log.debug("[ToolAttemptRetention] Prune sweep skipped — no ToolAttemptStorePort bean available");
            return;
        }

        pruneNow(Instant.now());
    }

    /**
     * Executes the pruning sweep against all workspaces using the given reference time.
     * Package-private to allow deterministic testing with a fixed clock.
     *
     * @param referenceNow the reference timestamp to compute the cutoff from
     * @return total number of records pruned across all workspaces
     */
    long pruneNow(Instant referenceNow) {
        if (storePortInstance.isUnsatisfied()) {
            return 0L;
        }
        ToolAttemptStorePort storePort = storePortInstance.get();
        Instant cutoff = referenceNow.minus(retentionDuration);
        Set<String> workspaceIds = storePort.listWorkspaceIds();
        long totalPruned = 0L;
        for (String workspaceId : workspaceIds) {
            try {
                totalPruned += WorkspaceContext.callWithActiveWorkspace(
                        workspaceId, () -> storePort.pruneTerminalBefore(workspaceId, cutoff));
            } catch (Exception e) {
                log.errorf(
                        LogUtil.relevantThrowable(e),
                        "[ToolAttemptRetention] Prune failed for workspace '%s': %s",
                        workspaceId,
                        LogUtil.rootMessage(e));
            }
        }
        log.infof(
                "[ToolAttemptRetention] Pruned %d terminal records across %d workspaces (cutoff=%s)",
                totalPruned, workspaceIds.size(), cutoff);
        return totalPruned;
    }
}
