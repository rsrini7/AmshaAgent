package com.amsha.app.scheduler;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.amsha.common.util.LogUtil;
import com.amsha.core.cron.CronTaskScheduled;
import com.amsha.core.domain.model.AgentCron;
import com.amsha.core.domain.model.AgentCronRecord;
import com.amsha.core.util.CronScheduleUtil;
import com.amsha.core.util.DatabaseSafetyGuard;
import com.amsha.core.util.MdcCleaner;
import com.amsha.core.util.MdcKeys;
import com.amsha.persistence.api.AdvisoryLockPort;
import com.amsha.persistence.api.CronStorePort;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;

/**
 * Polls {@code agent_cron} table and dispatches due cron jobs to the
 * {@link CronJobRunner}.
 * <p>
 * Architecture decisions:
 * <ul>
 * <li>ManagedExecutor for CDI context propagation + GraalVM AOT safety</li>
 * <li>Semaphore for concurrency limiting with Throwable safety</li>
 * <li>Leader election via {@link AdvisoryLockPort} (§T5-3)</li>
 * </ul>
 */
@ApplicationScoped
public class AgentCronScheduler {

    private static final Logger log = Logger.getLogger(AgentCronScheduler.class);

    @Inject
    CronStorePort cronStore;

    @Inject
    CronJobRunner jobRunner;

    @Inject
    ManagedExecutor vThreads;

    @Inject
    Instance<AdvisoryLockPort> advisoryLock;

    @Inject
    Instance<AgentCronScheduler> selfProxy;

    @ConfigProperty(name = "amsha.cron.max-concurrent", defaultValue = "10")
    int maxConcurrent;

    @ConfigProperty(name = "amsha.cron.max-lookback-window", defaultValue = "PT5M")
    Duration maxLookbackWindow;

    @ConfigProperty(name = "amsha.cron.startup-delay-ms", defaultValue = "5000")
    long startupDelayMs;

    private ScheduledExecutorService preciseScheduler;
    private volatile boolean active = true;

    Semaphore concurrencyLimiter;

    @PostConstruct
    void init() {
        this.active = true;
        this.concurrencyLimiter = new Semaphore(maxConcurrent);

        // Confirm resolution of the optional advisory lock port
        if (advisoryLock.isUnsatisfied()) {
            log.error("CRITICAL: AdvisoryLockPort implementation not found. Leader election will be disabled (§T5-3).");
        } else {
            log.infof(
                    "AdvisoryLockPort resolved successfully: %s",
                    advisoryLock.get().getClass().getSimpleName());
        }

        // Precise scheduler for one-shot jobs that are due soon.
        this.preciseScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "precise-cron-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * One-time sweep on startup to fail any jobs that were stuck AWAITING_APPROVAL
     * but whose sessions are now dead (as all sessions are non-persistent §5.17).
     */
    void onStartup(@Observes StartupEvent ev) {
        if (!active) return;
        sweepOrphanedApprovalJobs();
        scheduleStartupRecovery();
    }

    private void scheduleStartupRecovery() {
        if (!active || preciseScheduler == null) return;
        long delay = Math.max(0L, startupDelayMs);
        try {
            var _ = preciseScheduler.schedule(this::runStartupRecovery, delay, TimeUnit.MILLISECONDS);
            log.infof("AgentCronScheduler: startup recovery poll scheduled in %dms.", delay);
        } catch (RejectedExecutionException e) {
            if (active) {
                log.warnf("AgentCronScheduler: startup recovery poll was rejected: %s", LogUtil.rootMessage(e));
            }
        }
    }

    private void runStartupRecovery() {
        if (!active) return;
        try {
            AgentCronScheduler proxy = selfProxy.get();
            if (proxy != null) proxy.pollAndDispatch();
        } catch (RuntimeException e) {
            if (active) {
                log.errorf("AgentCronScheduler: startup recovery poll failed: %s", LogUtil.rootMessage(e));
            }
        }
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        this.active = false;
    }

    private void sweepOrphanedApprovalJobs() {
        if (!active) return;
        log.info("AgentCronScheduler: starting orphan sweep for AWAITING_APPROVAL jobs...");
        try {
            List<AgentCron> orphaned = cronStore.findAllAwaitingApproval().stream()
                    .map(AgentCronRecord::fromDto)
                    .collect(Collectors.<AgentCron>toList());
            if (orphaned.isEmpty()) {
                log.info("AgentCronScheduler: no orphaned approval jobs found.");
                return;
            }

            for (AgentCron job : orphaned) {
                log.warnf(
                        "AgentCronScheduler: failing orphaned approval job id=%s goal='%s'",
                        job.getId(), job.getGoal());
                cronStore.markFailed(job.getId(), "Orphaned - session expired before approval (server restart).");
            }
            log.infof("AgentCronScheduler: orphan sweep complete. %d job(s) updated.", orphaned.size());
        } catch (RuntimeException e) {
            if (DatabaseSafetyGuard.checkDatabaseFailure(e)) return;
            String msg = e.getMessage() != null ? e.getMessage() : "";
            boolean isShutdown = !active || msg.contains("pool is closed") || msg.contains("database closed");
            if (!isShutdown) {
                log.error("AgentCronScheduler: failed to sweep orphaned approval jobs", e);
            } else {
                log.infof(
                        "AgentCronScheduler: persistence closed during sweep (app shutting down) - suppression intentional.");
                log.debugf("Full shutdown race details:\n%s", LogUtil.trimmedStack(e));
            }
        } catch (Exception e) {
            if (DatabaseSafetyGuard.checkDatabaseFailure(e)) return;
            log.error("AgentCronScheduler: failed to sweep orphaned approval jobs", e);
        }
    }

    @PreDestroy
    void shutdown() {
        this.active = false;
        if (preciseScheduler != null) {
            preciseScheduler.shutdown();
            try {
                if (!preciseScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    preciseScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                preciseScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Schedules a one-shot job to fire exactly at its nextFireTime if it is due
     * soon.
     */
    public void scheduleSoon(String jobId) {
        if (!active || preciseScheduler == null) return;
        AgentCron job = cronStore.findById(jobId).map(AgentCronRecord::fromDto).orElse(null);
        if (job == null || !job.isRunOnce() || job.getNextFireTime() == null) return;

        long delayMillis =
                Duration.between(Instant.now(), job.getNextFireTime()).toMillis();

        if (delayMillis < 120_000) {
            log.infof("CRON-PRECISE-SCHEDULE id=%s, firing in %dms", job.getId(), Math.max(0, delayMillis));
            try {
                var _ = preciseScheduler.schedule(
                        () -> {
                            try {
                                selfProxy.get().executeSoonInternal(jobId);
                            } catch (Exception e) {
                                log.errorf("CRON-PRECISE-FAIL id=%s:\n%s", jobId, LogUtil.trimmedStack(e));
                            }
                        },
                        Math.max(0, delayMillis),
                        TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                if (active) {
                    log.warnf("CRON-PRECISE-SCHEDULE-REJECT id=%s: %s", jobId, LogUtil.rootMessage(e));
                }
            }
        }
    }

    @ActivateRequestContext
    public void executeSoonInternal(String jobId) {
        AgentCron job = cronStore.findById(jobId).map(AgentCronRecord::fromDto).orElse(null);
        if (job == null) return;

        MDC.put(MdcKeys.USER_ID, job.getUserId());
        try {
            String executionId = UUID.randomUUID().toString();
            if (cronStore.claimForFire(job.getId(), executionId)) {
                log.infof("CRON-PRECISE-FIRE id=%s deliveryId=%s", job.getId(), executionId);

                final String capturedUserId = (String) MDC.get(MdcKeys.USER_ID);
                try {
                    vThreads.execute(() -> {
                        if (capturedUserId != null) MDC.put(MdcKeys.USER_ID, capturedUserId);
                        try {
                            jobRunner.execute(job, executionId);
                        } catch (Exception e) {
                            log.errorf("CRON-EXEC-FAIL id=%s:\n%s", job.getId(), LogUtil.trimmedStack(e));
                        } finally {
                            MdcCleaner.clearAll();
                        }
                    });
                } catch (RuntimeException e) {
                    releasePreciseClaim(job.getId());
                    log.warnf("CRON-PRECISE-DISPATCH-REJECT id=%s: %s", job.getId(), LogUtil.rootMessage(e));
                }
            }
        } finally {
            MdcCleaner.clearAll();
        }
    }

    private void releasePreciseClaim(String jobId) {
        try {
            cronStore.releaseClaimToActive(jobId);
        } catch (RuntimeException e) {
            log.warnf("CRON-PRECISE-CLAIM-RELEASE-FAIL id=%s: %s", jobId, LogUtil.rootMessage(e));
        }
    }

    /**
     * Resets the concurrency limit.
     */
    void resetConcurrencyLimit(int permits) {
        log.warnf("CRON-RESET-LIMIT permits=%d", permits);
        this.concurrencyLimiter.drainPermits();
        this.concurrencyLimiter.release(permits);
    }

    int getAvailablePermits() {
        return this.concurrencyLimiter.availablePermits();
    }

    /**
     * Entry point for new tasks: schedules them for precise firing if due soon.
     */
    void onTaskScheduled(@Observes CronTaskScheduled event) {
        scheduleSoon(event.jobId());
    }

    /**
     * Polls for due cron jobs and dispatches them to managed virtual threads.
     * <p>
     * Step 0: Leader election (§T5-3)
     * Step 0.1: Crash recovery (reclaim RUNNING rows stuck > 5 minutes)
     * Step 1: Query due rows (read-only - no write lock)
     * Step 2: Claim → throttle check → dispatch via JobRunner
     */
    @Scheduled(every = "${amsha.cron.poll-interval:30s}", concurrentExecution = ConcurrentExecution.SKIP)
    @ActivateRequestContext
    @Transactional(TxType.NOT_SUPPORTED)
    void pollAndDispatch() {
        if (!active) {
            return;
        }

        try {
            // Step 0 - Leader election (§T5-3)
            if (!advisoryLock.isUnsatisfied() && !advisoryLock.get().tryLock("cron-leader", 30)) {
                return; // Not the leader this cycle
            }

            // Step 0.1 - Crash recovery (§5.12 isolated failure boundary)
            try {
                cronStore.reclaimStaleCrons(Instant.now().minus(5, ChronoUnit.MINUTES));
            } catch (Exception e) {
                if (DatabaseSafetyGuard.checkDatabaseFailure(e)) return;
                log.warnf("Zombie-cron reclaim failed (skipping cycle): %s", LogUtil.rootMessage(e));
                return;
            }

            // Step 1 - Query due rows
            List<AgentCron> due = cronStore.findDueCrons().stream()
                    .map(AgentCronRecord::fromDto)
                    .collect(Collectors.<AgentCron>toList());
            if (due.isEmpty()) return;

            Instant lookbackCutoff = Instant.now().minus(maxLookbackWindow);

            Iterator<AgentCron> iter = due.iterator();
            while (iter.hasNext()) {
                if (!active) return;
                AgentCron job = iter.next();
                if (job.getNextFireTime().isBefore(lookbackCutoff)) {
                    if (job.isRunOnce()) {
                        log.warnf(
                                "Cron one-shot job [%s] missed fire time %s - marking FAILED instead of rolling to a future occurrence.",
                                job.getId(), job.getNextFireTime());
                        cronStore.markFailed(
                                job.getId(), "One-shot schedule expired before execution and was not rescheduled.");
                    } else {
                        log.warnf(
                                "Cron job [%s] missed fire time %s - outside %s lookback window. Marking SKIPPED and rescheduling.",
                                job.getId(), job.getNextFireTime(), lookbackCutoff);
                        Instant newNext = (job.getCronExpression() != null
                                        && !job.getCronExpression().isBlank())
                                ? CronScheduleUtil.computeNext(job.getCronExpression(), job.getTimezone())
                                : null;
                        // A missed recurring occurrence is a skipped fire, not a terminal
                        // schedule state.  markSkipped stores SKIPPED, and findDueCrons only
                        // reads ACTIVE rows; use the normal reschedule transition so the job
                        // remains eligible for its next occurrence.
                        cronStore.markFired(job.getId(), Instant.now(), newNext);
                    }
                    iter.remove();
                }
            }

            if (due.isEmpty()) return;

            log.infof("CRON-POLL found %d due job(s)", due.size());

            // Step 2 - Dispatch loop
            for (AgentCron job : due) {
                if (!active) break;
                String executionId = UUID.randomUUID().toString();
                if (!cronStore.claimForFire(job.getId(), executionId)) continue;

                if (!concurrencyLimiter.tryAcquire()) {
                    log.warnf("CRON-THROTTLED cronId=%s, releasing claim", job.getId());
                    cronStore.releaseClaimToActive(job.getId());
                    continue;
                }

                try {
                    vThreads.execute(() -> {
                        MDC.put(MdcKeys.USER_ID, job.getUserId());
                        try {
                            jobRunner.execute(job, executionId);
                        } finally {
                            concurrencyLimiter.release();
                            MdcCleaner.clearAll();
                        }
                    });
                } catch (RejectedExecutionException e) {
                    log.errorf(
                            "Failed to dispatch cron job %s: %s",
                            job.getId(),
                            e.getMessage() != null
                                    ? e.getMessage()
                                    : e.getClass().getSimpleName());
                    concurrencyLimiter.release();
                    cronStore.releaseClaimToActive(job.getId());
                }
            }
        } catch (RuntimeException e) {
            if (DatabaseSafetyGuard.checkDatabaseFailure(e)) return;
            String msg = e.getMessage() != null ? e.getMessage() : "";
            boolean isShutdown = !active || msg.contains("pool is closed") || msg.contains("database closed");
            if (!isShutdown) {
                log.error("AgentCronScheduler: pollAndDispatch failed", e);
            } else {
                log.infof(
                        "AgentCronScheduler: database closed during pollAndDispatch (app shutting down) - suppression intentional.");
                log.debugf("Full shutdown race details:\n%s", LogUtil.trimmedStack(e));
            }
        } catch (Exception e) {
            if (DatabaseSafetyGuard.checkDatabaseFailure(e)) return;
            log.error("AgentCronScheduler: pollAndDispatch failed", e);
        }
    }
}
