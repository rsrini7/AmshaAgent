package com.amsha.core.agent.trace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.amsha.common.util.LogUtil;
import com.amsha.persistence.api.ExecutionTraceStorePort;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.quarkus.scheduler.Scheduled;

/**
 * Prunes expired durable execution trace records based on configurable retention.
 */
@ApplicationScoped
@Unremovable
public class TraceRetentionReaper {

    private static final Logger log = Logger.getLogger(TraceRetentionReaper.class);

    private final Instance<ExecutionTraceStorePort> traceStoreInstance;
    private final int retentionDays;
    private final int maxRunsPerSession;

    @Inject
    public TraceRetentionReaper(
            Instance<ExecutionTraceStorePort> traceStoreInstance,
            @ConfigProperty(name = "amsha.trace.retention.max-age-days", defaultValue = "7") int retentionDays,
            @ConfigProperty(name = "amsha.trace.retention.max-runs-per-session", defaultValue = "50")
                    int maxRunsPerSession) {
        this.traceStoreInstance = traceStoreInstance;
        this.retentionDays = retentionDays;
        this.maxRunsPerSession = maxRunsPerSession;
    }

    protected TraceRetentionReaper() {
        this(null, 7, 50);
    }

    /**
     * Daily scheduled retention sweep for expired execution trace records.
     */
    @Scheduled(
            every = "${amsha.trace.retention.interval:24h}",
            delayed = "PT20M",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void scheduledPrune() {
        pruneExpiredTraces();
    }

    /**
     * Executes the retention pruning job.
     * Prunes both by maximum age and by maximum runs per session.
     *
     * @return total count of pruned trace records
     */
    public long pruneExpiredTraces() {
        if (traceStoreInstance == null || traceStoreInstance.isUnsatisfied()) {
            return 0L;
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        long totalPruned = 0L;
        try {
            long prunedByAge = traceStoreInstance.get().pruneBefore(cutoff);
            if (prunedByAge > 0) {
                log.infof("TraceRetentionReaper: pruned %d trace records older than %s", prunedByAge, cutoff);
            }
            totalPruned += prunedByAge;
        } catch (Exception e) {
            log.warnf("TraceRetentionReaper: age pruning failed: %s", LogUtil.rootMessage(e));
        }

        try {
            long prunedByCount = traceStoreInstance.get().pruneExcessRuns(maxRunsPerSession);
            if (prunedByCount > 0) {
                log.infof(
                        "TraceRetentionReaper: pruned %d trace records exceeding max %d runs per session",
                        prunedByCount, maxRunsPerSession);
            }
            totalPruned += prunedByCount;
        } catch (Exception e) {
            log.warnf("TraceRetentionReaper: run count pruning failed: %s", LogUtil.rootMessage(e));
        }

        return totalPruned;
    }
}
