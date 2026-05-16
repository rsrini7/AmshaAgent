package com.arcclaw.app.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import com.arcclaw.core.cron.CronTaskScheduled;
import com.arcclaw.core.domain.model.AgentCron;
import com.arcclaw.core.domain.model.AgentCronRecord;
import com.arcclaw.core.util.CronScheduleUtil;
import com.arcclaw.core.util.LogUtil;
import com.arcclaw.core.util.MdcCleaner;
import com.arcclaw.core.util.MdcKeys;
import com.arcclaw.persistence.api.AdvisoryLockPort;
import com.arcclaw.persistence.api.CronStorePort;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Polls {@code agent_cron} table and dispatches due cron jobs to the {@link CronJobRunner}.
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

    @ConfigProperty(name = "arcclaw.cron.max-concurrent", defaultValue = "10")
    int maxConcurrent;

    @ConfigProperty(name = "arcclaw.cron.max-lookback-window", defaultValue = "PT5M")
    Duration maxLookbackWindow;

    @ConfigProperty(name = "arcclaw.cron.startup-delay-ms", defaultValue = "5000")
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
            log.infof("AdvisoryLockPort resolved successfully: %s", advisoryLock.get().getClass().getSimpleName());
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
        // Wait briefly for persistence adapters to stabilize without blocking virtual thread
        Uni.createFrom().voidItem()
           .onItem().delayIt().by(Duration.ofMillis(startupDelayMs))
           .subscribe().with(unused -> {
               if (active) sweepOrphanedApprovalJobs();
           }, failure -> log.error("Startup orphan sweep failed", failure));
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
                log.warnf("AgentCronScheduler: failing orphaned approval job id=%s goal='%s'",
                        job.getId(), job.getGoal());
                cronStore.markFailed(job.getId(), "Orphaned - session expired before approval (server restart).");
            }
            log.infof("AgentCronScheduler: orphan sweep complete. %d job(s) updated.", orphaned.size());
        } catch (IllegalStateException | PersistenceException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            boolean isShutdown = !active || msg.contains("EntityManagerFactory is closed");
            if (!isShutdown) {
                log.error("AgentCronScheduler: failed to sweep orphaned approval jobs", e);
            } else {
                log.infof("AgentCronScheduler: persistence closed during sweep (app shutting down) - suppression intentional.");
                log.debugf("Full shutdown race details:\n%s", LogUtil.trimmedStack(e));
            }
        } catch (Exception e) {
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
     * Schedules a one-shot job to fire exactly at its nextFireTime if it is due soon.
     */
    public void scheduleSoon(String jobId) {
        AgentCron job = cronStore.findById(jobId).map(AgentCronRecord::fromDto).orElse(null);
        if (job == null || !job.isRunOnce() || job.getNextFireTime() == null) return;
        
        long delayMillis = Duration.between(Instant.now(), job.getNextFireTime()).toMillis();
        
        if (delayMillis < 120_000) {
            log.infof("CRON-PRECISE-SCHEDULE id=%s, firing in %dms", job.getId(), Math.max(0, delayMillis));
            preciseScheduler.schedule(() -> {
                try {
                    selfProxy.get().executeSoonInternal(jobId);
                } catch (Exception e) {
                    log.errorf("CRON-PRECISE-FAIL id=%s:\n%s", jobId, LogUtil.trimmedStack(e));
                }
            }, Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
        }
    }

    @ActivateRequestContext
    public void executeSoonInternal(String jobId) {
        AgentCron job = cronStore.findById(jobId).map(AgentCronRecord::fromDto).orElse(null);
        if (job == null) return;

        MDC.put(MdcKeys.USER_ID, job.getUserId());
        try {
            if (cronStore.claimForFire(job.getId())) {
                 log.infof("CRON-PRECISE-FIRE id=%s", job.getId());
                 
                 final String capturedUserId = (String) MDC.get(MdcKeys.USER_ID);
                 vThreads.execute(() -> {
                     if (capturedUserId != null) MDC.put(MdcKeys.USER_ID, capturedUserId);
                     try {
                         jobRunner.execute(job);
                     } catch (Exception e) {
                          log.errorf("CRON-EXEC-FAIL id=%s:\n%s", job.getId(), LogUtil.trimmedStack(e));
                     } finally {
                         MdcCleaner.clearAll();
                     }
                 });
            }
        } finally {
            MdcCleaner.clearAll();
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
    @Scheduled(every = "${arcclaw.cron.poll-interval:30s}",
               concurrentExecution = ConcurrentExecution.SKIP)
    @ActivateRequestContext
    @Transactional(TxType.NOT_SUPPORTED)
    void pollAndDispatch() {
        // Step 0 - Leader election (§T5-3)
        if (!advisoryLock.isUnsatisfied() && !advisoryLock.get().tryLock("cron-leader", 30)) {
            return; // Not the leader this cycle
        }

        try {
            // Step 0.1 - Crash recovery
            cronStore.reclaimStaleCrons(
                    Instant.now().minus(5, ChronoUnit.MINUTES));
        } catch (Exception e) {
                log.warnf("Cron zombie recovery failed (skipping this cycle): %s",
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
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
            AgentCron job = iter.next();
            if (job.getNextFireTime().isBefore(lookbackCutoff)) {
                log.warnf("Cron job [%s] missed fire time %s - outside %s lookback window. Marking SKIPPED and rescheduling.",
                    job.getId(), job.getNextFireTime(), lookbackCutoff);
                Instant newNext = (job.getCronExpression() != null && !job.getCronExpression().isBlank())
                    ? CronScheduleUtil.computeNext(job.getCronExpression(), job.getTimezone())
                    : null;
                cronStore.markSkipped(job.getId(), newNext, Instant.now());
                iter.remove();
            }
        }

        if (due.isEmpty()) return;

        log.infof("CRON-POLL found %d due job(s)", due.size());

        // Step 2 - Dispatch loop
        for (AgentCron job : due) {
            if (!cronStore.claimForFire(job.getId())) continue;

            if (!concurrencyLimiter.tryAcquire()) {
                log.warnf("CRON-THROTTLED cronId=%s, releasing claim", job.getId());
                cronStore.releaseClaimToActive(job.getId());
                continue;
            }

            try {
                vThreads.execute(() -> {
                    MDC.put(MdcKeys.USER_ID, job.getUserId());
                    try {
                        jobRunner.execute(job);
                    } finally {
                        concurrencyLimiter.release();
                        MdcCleaner.clearAll();
                    }
                });
            } catch (RejectedExecutionException e) {
                log.errorf("Failed to dispatch cron job %s: %s",
                        job.getId(), e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                concurrencyLimiter.release();
                cronStore.releaseClaimToActive(job.getId());
            }
        }
    }
}
