package com.amsha.app.scheduler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.loop.CronRunResultMapper;
import com.amsha.core.agent.reasoning.LangChainReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.domain.model.AgentCron;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.persistence.api.CronRunDto;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

/**
 * Orchestrates the execution of a single cron job, managing the workspace context,
 * secret seeding, and hand-off to AgentLoop.
 */
@ApplicationScoped
public class CronJobRunner {

    private static final Logger log = Logger.getLogger(CronJobRunner.class);

    private static final Pattern CRON_PREFIX_PATTERN = Pattern.compile("^CRON\\s+");
    private static final Pattern PURE_ANNOUNCEMENT_PATTERN =
            Pattern.compile("^(?:print|say|tell|output(?: message)?|display|send(?: message| text)?).*");
    private static final Pattern ANNOUNCEMENT_PREFIX_PATTERN = Pattern.compile(
            "(?i)^(?:print|say|tell|output(?: message)?|display|send(?: message| text)?)(?:\\s+to\\s+(?:the\\s+)?(?:console|user|chat))?(?:\\s*[:=,])?\\s*");
    private static final Pattern ANNOUNCEMENT_SUFFIX_PATTERN =
            Pattern.compile("(?i)(?:\\s+to\\s+(?:the\\s+)?(?:console|user|stdout|chat)|\\s+in\\s+(?:the\\s+)?chat)$");

    @Inject
    AgentLoop agentLoop;

    @Inject
    LangChainReasoningEngine engine;

    @Inject
    CronResultHandler resultHandler;

    @Inject
    CronStorePort cronStore;

    @Inject
    CronRunStorePort cronRunStore;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @Inject
    ManagedExecutor vThreads;

    @ConfigProperty(name = "amsha.cron.max-iterations", defaultValue = "5")
    int cronMaxIterations;

    public void execute(AgentCron job) {
        execute(job, UUID.randomUUID().toString());
    }

    public void execute(AgentCron job, String executionId) {
        String cronSessionId = AgentSentinels.CRON_SESSION_PREFIX + job.getId() + "-" + executionId;

        // Record cron run start
        CronRunDto run = new CronRunDto(
                executionId,
                job.getId(),
                cronSessionId,
                CronRunResultMapper.STATUS_RUNNING,
                Instant.now(),
                null,
                null,
                0,
                0,
                0,
                BigDecimal.ZERO,
                Instant.now());
        cronRunStore.save(run);

        heartbeatTracker.recordHeartbeat(cronSessionId, AutonomyLevel.SUPERVISED);

        Thread executionThread = Thread.currentThread();
        AtomicBoolean running = new AtomicBoolean(true);
        Future<?> leaseFuture = null;

        try {
            leaseFuture = vThreads.submit(WorkspaceScopeHolder.wrap(() -> {
                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(60_000);
                        if (!running.get()) break;
                        boolean renewed = cronStore.renewLease(job.getId(), executionId);
                        if (!renewed) {
                            log.warnf(
                                    "Cron lease lost or stolen for cronId=%s executionId=%s - halting session",
                                    job.getId(), executionId);
                            WorkspaceManager.haltSession(cronSessionId);
                            executionThread.interrupt();
                            break;
                        }
                        heartbeatTracker.recordHeartbeat(cronSessionId, AutonomyLevel.SUPERVISED);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.warnf(
                                "Cron lease renewal failed for cronId=%s executionId=%s; halting session: %s",
                                job.getId(), executionId, LogUtil.rootMessage(e));
                        WorkspaceManager.haltSession(cronSessionId);
                        executionThread.interrupt();
                        break;
                    }
                }
            }));

            ChannelType type = ChannelType.fromString(job.getChannelType())
                    .orElseThrow(() -> new IllegalArgumentException("Cron job " + job.getId()
                            + " has missing or unknown channelType: " + job.getChannelType()));
            String stripped = job.getGoal() != null
                    ? CRON_PREFIX_PATTERN
                            .matcher(job.getGoal())
                            .replaceFirst("")
                            .trim()
                    : "";

            if (isPureAnnouncement(stripped)) {
                String announcement = stripAnnouncementPrefix(stripped);
                log.infof("CRON-SHORT-CIRCUIT cronId=%s announcement='%s'", job.getId(), announcement);
                AgentResult result = AgentResult.finished(announcement, Collections.emptyList());
                resultHandler.handleSuccess(job, cronSessionId, executionId, result);
                return;
            }

            if (isTimeCheck(stripped.toLowerCase(Locale.ROOT))) {
                log.infof("CRON-SHORT-CIRCUIT cronId=%s time-check", job.getId());
                String time = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME);
                AgentResult result = AgentResult.finished("The current time is " + time, Collections.emptyList());
                resultHandler.handleSuccess(job, cronSessionId, executionId, result);
                return;
            }

            String finalGoal = unwrapToolCallGoal(stripped);
            if (!finalGoal.equals(stripped)) {
                log.infof("CRON-UNWRAP cronId=%s: extracted goal '%s'", job.getId(), finalGoal);
            }

            String systemNote = "CRON MODE";

            String role = resolveRole(finalGoal, job.getExecutionRole());
            String channel = type.name();

            Callable<AgentResult> op = () -> {
                MessageContext context = createCronContext(job, cronSessionId, finalGoal, type);
                return agentLoop.run(cronSessionId, role, finalGoal, systemNote, engine, cronMaxIterations, context);
            };

            AgentResult result = WorkspaceContext.<AgentResult>run(
                    cronSessionId, job.getUserId(), job.getChatId(), AutonomyLevel.SUPERVISED, role, channel, op);

            resultHandler.handleSuccess(job, cronSessionId, executionId, result);

        } catch (Throwable t) {
            resultHandler.handleFailure(job, cronSessionId, executionId, t);
        } finally {
            running.set(false);
            if (leaseFuture != null) {
                leaseFuture.cancel(true);
            }
        }
    }

    private boolean isPureAnnouncement(String goal) {
        if (goal == null || goal.isBlank()) return false;
        String normalized = goal.toLowerCase(Locale.ROOT).trim();

        // Explicit announcement prefixes
        if (PURE_ANNOUNCEMENT_PATTERN.matcher(normalized).matches()) {
            return true;
        }

        // Check for lack of tool-like verbs (heuristic for simple statements)
        // NOTE: These keywords are intentionally broad. The guard's purpose is to
        // route any goal that implies agent work into the agent loop, not to be selective.
        boolean hasActionVerb = normalized.contains("fetch")
                || normalized.contains("search")
                || normalized.contains("check")
                || normalized.contains("monitor")
                || normalized.contains("run")
                || normalized.contains("execute")
                || normalized.contains("investigate")
                || normalized.contains("analyze")
                || normalized.contains("view")
                || normalized.contains("list")
                || normalized.contains("delete")
                || normalized.contains("update")
                || normalized.contains("create")
                || normalized.contains("google")
                || normalized.contains("memory")
                || normalized.contains("browse")
                || normalized.contains("find")
                || normalized.contains("retrieve")
                || normalized.contains("get")
                || normalized.contains("write")
                || normalized.contains("summarize")
                || normalized.contains("read")
                || normalized.contains("extract")
                || normalized.contains("identify")
                || normalized.contains(ToolCapability.GITHUB.toolName())
                || normalized.contains(ToolCapability.BROWSER_NAVIGATE.toolName())
                || normalized.contains(ToolCapability.WEB_SEARCH_SERPER.toolName());

        // §Issue: Exclude dynamic triggers from short-circuit.
        // If the message is a specific time check, we want the specialized short-circuit later.
        if (isTimeCheck(normalized) || normalized.contains("now") || normalized.contains("date")) {
            return false;
        }

        return !hasActionVerb && goal.length() < 200;
    }

    private boolean isTimeCheck(String normalized) {
        if (normalized == null || normalized.isBlank()) return false;
        return normalized.contains("current time")
                || normalized.contains("current utc time")
                || normalized.contains("current system time")
                || normalized.contains("what time is it")
                || normalized.contains("tell me the time")
                || normalized.equals("check: what is the current time?");
    }

    private String stripAnnouncementPrefix(String goal) {
        if (goal == null) return null;
        String result = goal.trim();

        result = ANNOUNCEMENT_PREFIX_PATTERN.matcher(result).replaceFirst("").trim();
        result = ANNOUNCEMENT_SUFFIX_PATTERN.matcher(result).replaceFirst("").trim();

        // Strip surrounding quotes if present
        if (result.startsWith("'") && result.endsWith("'") && result.length() > 2) {
            result = result.substring(1, result.length() - 1).trim();
        } else if (result.startsWith("\"") && result.endsWith("\"") && result.length() > 2) {
            result = result.substring(1, result.length() - 1).trim();
        }

        return result;
    }
    /**
     * §New Issue 1: Surgically extract the natural language goal from a serialized tool call string.
     * Example: garuda(agent="saraswati", goal="github repo view ...") -> "github repo view ..."
     */
    private String unwrapToolCallGoal(String goal) {
        if (goal == null || goal.isBlank()) return goal;

        // 1. Detect garuda(...) wrapper
        if (goal.startsWith(ToolCapability.DELEGATE_TO_AGENT.toolName()) && goal.contains("goal=")) {
            int goalStart = goal.indexOf("goal=\"");
            if (goalStart != -1) {
                int start = goalStart + 6;
                // §Issue 2: Safer quote scanning that respects escapes
                int end = -1;
                boolean escaped = false;
                for (int i = start; i < goal.length(); i++) {
                    char c = goal.charAt(i);
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        end = i;
                        break;
                    }
                }
                if (end != -1) {
                    return goal.substring(start, end).replace("\\\"", "\"").trim();
                }
            }
        }

        // 2. Detect JSON-like tool call if LLM stored raw JSON
        if (goal.startsWith("{") && goal.contains("\"goal\"")) {
            try {
                JsonNode node = new ObjectMapper().readTree(goal);
                if (node.has("goal")) return node.get("goal").asText().trim();
            } catch (Exception e) {
                log.warnf("Failed to parse goal JSON, using original: %s", LogUtil.rootMessage(e));
            }
        }

        return goal;
    }

    private String resolveRole(String goal, String explicitRole) {
        // Priority 0 - Explicit stored role always wins
        if (explicitRole != null && !explicitRole.isBlank()) {
            return explicitRole;
        }

        String normalized = goal != null ? goal.toLowerCase(Locale.ROOT).trim() : "";

        // Output-only goals: unconditionally override to coordinator
        if (normalized.startsWith("output")
                || normalized.startsWith("say ")
                || normalized.startsWith("send ")
                || normalized.startsWith("tell ")) {
            return AgentRoleEnum.COORDINATOR.toScopedName();
        }

        // §Refined Resolve:
        // 1. If it's a RAW tool call string (not yet unwrapped or intentional), run as COORDINATOR
        if (normalized.startsWith(ToolCapability.DELEGATE_TO_AGENT.toolName())) {
            return AgentRoleEnum.COORDINATOR.toScopedName();
        }

        // 2. If it's a github/browser/google command, go DIRECT to researcher (Issue 2)
        if (normalized.startsWith(ToolCapability.GITHUB.toolName())
                || normalized.startsWith(ToolCapability.BROWSER_NAVIGATE.toolName())
                || normalized.startsWith(ToolCapability.WEB_SEARCH_SERPER.toolName())
                || normalized.startsWith("browse")
                || normalized.startsWith("google")
                || normalized.startsWith("search")) {
            return AgentRoleEnum.RESEARCHER.toScopedName();
        }

        // 3. Coordination hints
        if (normalized.contains("coordinator task")
                || normalized.contains("meta-schedule")
                || normalized.contains("schedule next")) {
            return AgentRoleEnum.COORDINATOR.toScopedName();
        }

        // 4. Role keywords
        if (normalized.contains("researcher task")
                || normalized.contains("fetch")
                || normalized.contains("discovery")) {
            return AgentRoleEnum.RESEARCHER.toScopedName();
        } else if (normalized.contains("coder task")
                || normalized.contains("write code")
                || normalized.contains("write python")
                || normalized.contains("write script")
                || normalized.contains("write program")
                || normalized.contains("fix bug")
                || normalized.contains("refactor")
                || normalized.contains("echo ")
                || normalized.contains("bash ")
                || normalized.contains("python ")
                || normalized.contains("run ")
                || normalized.contains("execute ")) {
            // NOTE: "write program" is intentionally broad — false positives (e.g. "write program spec")
            // are acceptable since CODER will delegate or fail gracefully rather than silently mismatch.
            return AgentRoleEnum.CODER.toScopedName();
        }

        // Default to coordinator for cron tasks to allow accurate delegation if specialist is unclear
        return AgentRoleEnum.COORDINATOR.toScopedName();
    }

    private MessageContext createCronContext(AgentCron job, String sessionId, String goal, ChannelType channelType) {
        String platformId = job.getChannelPlatformId();
        if (platformId == null || platformId.isBlank()) {
            platformId = job.getChatId();
        }
        if (platformId == null || platformId.isBlank()) {
            throw new IllegalArgumentException("Cron job " + job.getId() + " has no channel platform or chat ID");
        }
        String chatId = job.getChatId();
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("Cron job " + job.getId() + " has no channel chat ID");
        }
        ChannelUser user = new ChannelUser(platformId, chatId, "Cron User", channelType);
        ChannelMessage msg =
                new ChannelMessage.GenericChannelMessage(user, goal, sessionId, Instant.now(), channelType);
        return new MessageContext(chatId, user, msg);
    }
}
