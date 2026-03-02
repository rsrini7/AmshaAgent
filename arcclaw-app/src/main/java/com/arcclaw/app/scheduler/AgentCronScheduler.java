package com.arcclaw.app.scheduler;

import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.AgentRoleEnum;
import com.arcclaw.core.agent.LangChainReasoningEngine;
import com.arcclaw.core.agent.RoleRegistry;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.apache.camel.ProducerTemplate;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.lang.ScopedValue;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Polls {@code agentcron} table and dispatches due cron jobs to the AgentLoop.
 * <p>
 * Architecture decisions (see ARCHITECTURAL_DECISIONS.md AD-017):
 * <ul>
 * <li>ManagedExecutor (not raw virtual threads) for CDI context propagation + GraalVM AOT safety</li>
 * <li>Split transactions (read then claim) to avoid SQLite SQLITE_BUSY</li>
 * <li>Semaphore for concurrency limiting with Throwable safety</li>
 * <li>BACKGROUND autonomy level for cron-fired sessions</li>
 * <li>Isolated session IDs ("cron-{id}") for zero chat history loading</li>
 * <li>Delivery order: AgentLoop → Telegram → DB update (recoverable on failure)</li>
 * </ul>
 */
@ApplicationScoped
public class AgentCronScheduler {

    private static final Logger log = Logger.getLogger(AgentCronScheduler.class);

    @Inject
    AgentCronRepository cronRepository;

    @Inject
    AgentLoop agentLoop;

    @Inject
    RoleRegistry roleRegistry;

    @Inject
    LangChainReasoningEngine engine;

    @Inject
    AuditService auditService;


    @Inject
    ProducerTemplate camelProducer;

    @Inject
    ManagedExecutor vThreads;

    @ConfigProperty(name = "arcclaw.channel.telegram.bot-token")
    String botToken;

    @ConfigProperty(name = "arcclaw.cron.max-concurrent", defaultValue = "10")
    int maxConcurrent;

    @ConfigProperty(name = "arcclaw.cron.max-iterations", defaultValue = "2")
    int cronMaxIterations;

    private String telegramEndpoint;
    private Semaphore concurrencyLimiter;

    /**
     * Build endpoint URI and semaphore ONCE at startup — not per message.
     */
    @PostConstruct
    void init() {
        this.telegramEndpoint = "telegram:bots?authorizationToken=" + botToken;
        this.concurrencyLimiter = new Semaphore(maxConcurrent);
    }

    /**
     * Polls for due cron jobs and dispatches them to managed virtual threads.
     * <p>
     * Step 0: Crash recovery (reclaim RUNNING rows stuck > 5 minutes)
     * Step 1: Query due rows (read-only — no write lock)
     * Step 2: Claim → throttle check → dispatch via ScopedValue binding
     */
    @Scheduled(every = "${arcclaw.cron.poll-interval:30s}",
               concurrentExecution = ConcurrentExecution.SKIP)
    @ActivateRequestContext
    void pollAndDispatch() {
        // Step 0 — Crash recovery
        cronRepository.reclaimStaleCrons(
                Instant.now().minus(5, ChronoUnit.MINUTES));

        // Step 1 — Query due rows (read-only transaction)
        List<AgentCronEntity> due = cronRepository.findDueCrons();
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
                try {
                    ScopedValue.where(WorkspaceScopeHolder.USER_ID, job.userId)
                            .where(WorkspaceScopeHolder.CHAT_ID, job.chatId)
                            .run(() -> WorkspaceScopeHolder.run(
                                    job.sessionId, () -> {
                                        executeCronJob(job);
                                    }
                            ));
                } catch (Throwable t) {
                    // Outer Throwable catches OOME before scope binding
                    concurrencyLimiter.release();
                    cronRepository.markFailed(job.id,
                            "Failed to bind scope: " + t.getMessage());
                    log.errorf(t, "CRON-SCOPE-FAIL cronId=%s", job.id);
                }
            });
        }
    }

    /**
     * Executes a single cron job: set BACKGROUND autonomy → run AgentLoop
     * with isolated session → deliver via Telegram → update DB state.
     */
    private void executeCronJob(AgentCronEntity job) {
        
        // FIXED: Hoist these outside the try block so the catch block can access the exact execution ID
        String executionId = UUID.randomUUID().toString();
        // Isolated session per FIRE — prevents history buildup across recurring jobs!
        String cronSessionId = "cron-" + job.id + "-" + executionId;
        
        try {
            MDC.put("userId", job.userId);

            // so it is available when TelegramAgentHandler queries it during HITL
            cronRepository.setDeliveryId(job.id, executionId);

            AgentResult result = WorkspaceContext.run(cronSessionId, job.userId, job.chatId, AutonomyLevel.BACKGROUND, AgentRoleEnum.COORDINATOR.toScopedName(), () -> 
                agentLoop.run(
                    cronSessionId,
                    AgentRoleEnum.COORDINATOR.toScopedName(),
                    job.goal,
                    engine,
                    cronMaxIterations
                )
            );

            boolean wasApprovalRequested = result.awaitingApproval();
            String rawAnswer;

            if (wasApprovalRequested) {
                rawAnswer = "⚠️ Approval Required for scheduled task.\n\n" +
                    "Cron ID: " + job.id + "\n" +
                    "Tool: github\n" +
                    "Goal: " + job.goal + "\n\n" +
                    "Reply `/approve " + job.id + "` to run it once now, or `/deny " + job.id + "` to cancel.";
            } else {
                rawAnswer = result.finalAnswer() != null ? result.finalAnswer() : "";
            }
            
            // Defensive Guard against empty LLM outputs crashing Telegram
            String finalAnswer = rawAnswer.trim().isEmpty() 
                    ? "⚠️ Task executed, but the agent returned an empty response for goal: " + job.goal 
                    : rawAnswer;

            // DELIVERY ORDER: idempotency key → Telegram → DB update
            // We can safely reuse the executionId as our delivery idempotency key
            cronRepository.setDeliveryId(job.id, executionId);

            camelProducer.sendBodyAndHeaders(
                    telegramEndpoint, finalAnswer,
                    new HashMap<>(Map.of("CamelTelegramChatId", job.chatId))
            );

            // Only mark successful completions if it wasn't paused for approval
            if (!wasApprovalRequested) {
                if (job.runOnce) {
                    cronRepository.markCompleted(job.id, Instant.now());
                    auditService.record(cronSessionId, job.userId,
                            AgentRoleEnum.COORDINATOR.toScopedName(),
                            SecurityEventType.CRONFIRED,
                            "cronId=" + job.id + " runOnce=true status=COMPLETED");
                    log.infof("CRON-COMPLETED one-shot cronId=%s", job.id);
                } else {
                    ZonedDateTime next = ExecutionTime
                            .forCron(new CronParser(
                                    CronDefinitionBuilder.instanceDefinitionFor(
                                            CronType.UNIX))
                                    .parse(job.cronExpression))
                            .nextExecution(ZonedDateTime.now(ZoneId.of(job.timezone)))
                            .orElseThrow(() -> new IllegalStateException(
                                    "No next execution for: " + job.cronExpression));
                    cronRepository.markFired(job.id, Instant.now(), next.toInstant());
                    auditService.record(cronSessionId, job.userId,
                            AgentRoleEnum.COORDINATOR.toScopedName(),
                            SecurityEventType.CRONFIRED,
                            "cronId=" + job.id + " runOnce=false nextFire=" + next);
                    log.infof("CRON-FIRED cronId=%s nextFire=%s", job.id, next);
                }
            }

        } catch (Throwable t) {
            // Inner Throwable catches OOME during AgentLoop/Telegram
            cronRepository.markFailed(job.id, t.getMessage());
            
            // FIXED: Use the hoisted cronSessionId containing the execution UUID.
            auditService.record(cronSessionId, job.userId,
                    AgentRoleEnum.COORDINATOR.toScopedName(),
                    SecurityEventType.CRONFAILED,
                    "cronId=" + job.id + " reason=" + t.getMessage());

            // Avoid log pollution for expected test failures
            if (t.getMessage() != null && t.getMessage().contains("[TEST-EXPECTED]")) {
                log.warnf("CRON-FAIL (EXPECTED) cronId=%s: %s", job.id, t.getMessage());
            } else {
                log.warnf(t, "CRON-FAIL cronId=%s", job.id);
            }
            
            try {
                camelProducer.sendBodyAndHeaders(
                        telegramEndpoint,
                        "❌ Scheduled task failed.\n\nReason: "
                            + AgentErrorMessageUtil.sanitizeErrorMessage(t.getMessage()),
                        new HashMap<>(Map.of("CamelTelegramChatId", job.chatId))
                );
            } catch (Exception notifyEx) {
                log.warnf(notifyEx, "CRON-NOTIFY-FAIL cronId=%s", job.id);
            }
        } finally {
            concurrencyLimiter.release();
            MDC.remove("userId");
        }
    }
}