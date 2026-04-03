package com.arcclaw.app.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.CronStatus;
import com.arcclaw.core.util.CronScheduleUtil;
import com.arcclaw.core.util.MdcCleaner;
import com.arcclaw.core.util.MdcKeys;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import jakarta.transaction.Transactional.TxType;

/**
 * Polls {@code agentcron} table and dispatches due cron jobs to the {@link CronJobRunner}.
 * <p>
 * Architecture decisions:
 * <ul>
 * <li>ManagedExecutor for CDI context propagation + GraalVM AOT safety</li>
 * <li>Semaphore for concurrency limiting with Throwable safety</li>
 * </ul>
 */
@ApplicationScoped
public class AgentCronScheduler {

    private static final Logger log = Logger.getLogger(AgentCronScheduler.class);

    @Inject
    AgentCronRepository cronRepository;

    @Inject
    CronJobRunner jobRunner;

    @Inject
    ManagedExecutor vThreads;

    @ConfigProperty(name = "arcclaw.cron.max-concurrent", defaultValue = "10")
    int maxConcurrent;

    @ConfigProperty(name = "arcclaw.cron.max-lookback-window", defaultValue = "PT5M")
    Duration maxLookbackWindow;

    Semaphore concurrencyLimiter;

    @PostConstruct
    void init() {
        this.concurrencyLimiter = new Semaphore(maxConcurrent);
    }

    /**
     * Resets the concurrency limit to a specific number of permits.
     * // @VisibleForTesting — mutates live semaphore state, do not call from production code
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
     * Polls for due cron jobs and dispatches them to managed virtual threads.
     * <p>
     * Step 0: Crash recovery (reclaim RUNNING rows stuck > 5 minutes)
     * Step 1: Query due rows (read-only — no write lock)
     * Step 2: Claim → throttle check → dispatch via JobRunner
     */
    @Scheduled(every = "${arcclaw.cron.poll-interval:30s}",
               concurrentExecution = ConcurrentExecution.SKIP)
    @ActivateRequestContext
    @Transactional(TxType.NOT_SUPPORTED)
    void pollAndDispatch() {
        try {
            // Step 0 — Crash recovery
            cronRepository.reclaimStaleCrons(
                    Instant.now().minus(5, ChronoUnit.MINUTES));
        } catch (Exception e) {
                // Isolate: a broken connection/Narayana state must not abort the whole poll cycle
                log.warnf("Cron zombie recovery failed (skipping this cycle): %s",
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                return;
        }
        
        // Step 1 — Query due rows (read-only transaction)
        List<AgentCronEntity> due = cronRepository.findDueCrons();
        if (due.isEmpty()) return;

        Instant lookbackCutoff = Instant.now().minus(maxLookbackWindow);
        List<AgentCronEntity> skippedJobs = new ArrayList<>();

        Iterator<AgentCronEntity> iter = due.iterator();
        while (iter.hasNext()) {
            AgentCronEntity job = iter.next();
            if (job.nextFireTime.isBefore(lookbackCutoff)) {
                log.warnf("Cron job [%s] missed fire time %s — outside %s lookback window. Marking SKIPPED and rescheduling.",
                    job.id, job.nextFireTime, maxLookbackWindow);
                job.status = CronStatus.SKIPPED;
                job.nextFireTime = CronScheduleUtil.computeNext(job.cronExpression, job.timezone);
                skippedJobs.add(job);
                iter.remove();
            }
        }

        if (!skippedJobs.isEmpty()) {
            cronRepository.markSkipped(skippedJobs);
        }

        if (due.isEmpty()) return;

        log.infof("CRON-POLL found %d due job(s)", due.size());

        // Step 2 — Dispatch loop
        for (AgentCronEntity job : due) {
            // Atomic claim — if another thread beat us, skip
            if (!cronRepository.claimForFire(job.id)) continue;

            // Throttle check — release claim for next poll, don't kill the job
            if (!concurrencyLimiter.tryAcquire()) {
                log.warnf("CRON-THROTTLED cronId=%s, releasing claim", job.id);
                cronRepository.releaseClaimToActive(job.id);
                continue;
            }

            // Dispatch to managed virtual thread
            vThreads.execute(() -> {
                MDC.put(MdcKeys.USER_ID, job.userId);
                try {
                    jobRunner.execute(job);
                } finally {
                    concurrencyLimiter.release();
                    MdcCleaner.clearAll();
                }
            });
        }
    }
}
