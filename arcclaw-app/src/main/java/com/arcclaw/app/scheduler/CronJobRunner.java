package com.arcclaw.app.scheduler;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.LangChainReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.domain.model.AgentCron;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.persistence.api.CronStorePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Orchestrates the execution of a single cron job, managing the workspace context,
 * secret seeding, and hand-off to AgentLoop.
 */
@ApplicationScoped
public class CronJobRunner {

    private static final Logger log = Logger.getLogger(CronJobRunner.class);

    private static final Pattern CRON_PREFIX_PATTERN = Pattern.compile("^CRON\\s+");
    private static final Pattern PURE_ANNOUNCEMENT_PATTERN = Pattern.compile("^(?:print|say|tell|output(?: message)?|display|send(?: message| text)?).*");
    private static final Pattern ANNOUNCEMENT_PREFIX_PATTERN = Pattern.compile("(?i)^(?:print|say|tell|output(?: message)?|display|send(?: message| text)?)(?:\\s+to\\s+(?:the\\s+)?(?:console|user|chat))?(?:\\s*[:=,])?\\s*");
    private static final Pattern ANNOUNCEMENT_SUFFIX_PATTERN = Pattern.compile("(?i)(?:\\s+to\\s+(?:the\\s+)?(?:console|user|stdout|chat)|\\s+in\\s+(?:the\\s+)?chat)$");

    @Inject
    AgentLoop agentLoop;

    @Inject
    LangChainReasoningEngine engine;

    @Inject
    CronResultHandler resultHandler;

    @Inject
    CronStorePort cronStore;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @ConfigProperty(name = "arcclaw.cron.max-iterations", defaultValue = "3")
    int cronMaxIterations;



    public void execute(AgentCron job) {
        String executionId = UUID.randomUUID().toString();
        
        cronStore.setDeliveryId(job.getId(), executionId);

        String cronSessionId = AgentSentinels.CRON_SESSION_PREFIX + job.getId() + "-" + executionId;

        heartbeatTracker.recordHeartbeat(cronSessionId, AutonomyLevel.SUPERVISED);
        
        try {
            String stripped = job.getGoal() != null ? CRON_PREFIX_PATTERN.matcher(job.getGoal()).replaceFirst("").trim() : "";
            
            if (isPureAnnouncement(stripped)) {
                String announcement = stripAnnouncementPrefix(stripped);
                log.infof("CRON-SHORT-CIRCUIT cronId=%s announcement='%s'", job.getId(), announcement);
                AgentResult result = AgentResult.finished(announcement, Collections.emptyList());
                resultHandler.handleSuccess(job, cronSessionId, executionId, result);
                return;
            }

            if (isTimeCheck(stripped.toLowerCase())) {
                log.infof("CRON-SHORT-CIRCUIT cronId=%s time-check", job.getId());
                String time = ZonedDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.RFC_1123_DATE_TIME);
                AgentResult result = AgentResult.finished("The current time is " + time, Collections.emptyList());
                resultHandler.handleSuccess(job, cronSessionId, executionId, result);
                return;
            }

            String finalGoal = unwrapToolCallGoal(stripped);
            if (!finalGoal.equals(stripped)) {
                log.infof("CRON-UNWRAP cronId=%s: extracted goal '%s'", job.getId(), finalGoal);
            }

            String systemNote = "CRON MODE";

            ChannelType type = ChannelType.fromString(job.getChannelType()).orElse(ChannelType.TELEGRAM);
            String role = resolveRole(finalGoal, job.getExecutionRole());
            String channel = type.name();

            Callable<AgentResult> op = () -> {
                MessageContext context = createCronContext(job, cronSessionId, executionId, finalGoal, type);
                return agentLoop.run(
                    cronSessionId,
                    role,
                    finalGoal,
                    systemNote,
                    engine,
                    cronMaxIterations,
                    context
                );
            };

            AgentResult result = WorkspaceContext.<AgentResult>run(
                cronSessionId, job.getUserId(), job.getChatId(), AutonomyLevel.SUPERVISED, role, channel, op
            );

            resultHandler.handleSuccess(job, cronSessionId, executionId, result);

        } catch (Throwable t) {
            resultHandler.handleFailure(job, cronSessionId, executionId, t);
        }
    }

    private boolean isPureAnnouncement(String goal) {
        if (goal == null || goal.isBlank()) return false;
        String normalized = goal.toLowerCase().trim();
        
        // Explicit announcement prefixes
        if (PURE_ANNOUNCEMENT_PATTERN.matcher(normalized).matches()) {
            return true;
        }

        // Check for lack of tool-like verbs (heuristic for simple statements)
        // NOTE: These keywords are intentionally broad. The guard's purpose is to
        // route any goal that implies agent work into the agent loop, not to be selective.
        boolean hasActionVerb = normalized.contains("fetch") || normalized.contains("search") 
                || normalized.contains("check") || normalized.contains("monitor") 
                || normalized.contains("run") || normalized.contains("execute")
                || normalized.contains("investigate") || normalized.contains("analyze")
                || normalized.contains("view") || normalized.contains("list")
                || normalized.contains("delete") || normalized.contains("update")
                || normalized.contains("create") || normalized.contains("google")
                || normalized.contains("memory") || normalized.contains("browse")
                || normalized.contains("find") || normalized.contains("retrieve")
                || normalized.contains("get") || normalized.contains("write")
                || normalized.contains("summarize") || normalized.contains("read")
                || normalized.contains("extract") || normalized.contains("identify")
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
        return normalized.contains("what is the current time") || 
               normalized.contains("what time is it") ||
               normalized.contains("tell me the time") ||
               normalized.contains("with the current time") ||
               normalized.contains("show the current time") ||
               normalized.equals("check: what is the current time?");
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
     * Example: delegate_to_agent(agent="saraswati", goal="github repo view ...") -> "github repo view ..."
     */
    private String unwrapToolCallGoal(String goal) {
        if (goal == null || goal.isBlank()) return goal;
        
        // 1. Detect delegate_to_agent(...) wrapper
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
                log.warnf("Failed to parse goal JSON, using original: %s", ExceptionUtils.getRootCauseMessage(e));
            }
        }
        
        return goal;
    }

    private String resolveRole(String goal, String explicitRole) {
        // Priority 0 - Explicit stored role always wins
        if (explicitRole != null && !explicitRole.isBlank()) {
            return explicitRole;
        }

        String normalized = goal != null ? goal.toLowerCase().trim() : "";
        
        // Output-only goals: unconditionally override to coordinator
        if (normalized.startsWith("output") || normalized.startsWith("say ")
                || normalized.startsWith("send ") || normalized.startsWith("tell ")) {
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
                || normalized.startsWith("browse") || normalized.startsWith("google")
                || normalized.startsWith("search")) {
            return AgentRoleEnum.RESEARCHER.toScopedName();
        }

        // 3. Coordination hints
        if (normalized.contains("coordinator task") || normalized.contains("meta-schedule") || normalized.contains("schedule next")) {
            return AgentRoleEnum.COORDINATOR.toScopedName();
        }
        
        // 4. Role keywords
        if (normalized.contains("researcher task") || normalized.contains("fetch") || normalized.contains("discovery")) {
            return AgentRoleEnum.RESEARCHER.toScopedName();
        } else if (normalized.contains("coder task") || normalized.contains("write code") || normalized.contains("write python")
                || normalized.contains("write script") || normalized.contains("write program")
                || normalized.contains("fix bug") || normalized.contains("refactor")
                || normalized.contains("echo ") || normalized.contains("bash ") || normalized.contains("python ")
                || normalized.contains("run ") || normalized.contains("execute ")) {
            // NOTE: "write program" is intentionally broad — false positives (e.g. "write program spec")
            // are acceptable since CODER will delegate or fail gracefully rather than silently mismatch.
            return AgentRoleEnum.CODER.toScopedName();
        }
        
        // Default to coordinator for cron tasks to allow accurate delegation if specialist is unclear
        return AgentRoleEnum.COORDINATOR.toScopedName();
    }

    private MessageContext createCronContext(AgentCron job, String sessionId, String executionId, String goal, ChannelType channelType) {
        ChannelUser user = new ChannelUser(job.getUserId(), job.getUserId(), "Cron User", channelType);
        ChannelMessage msg = new ChannelMessage.RestApiMessage(user, goal, AgentSentinels.CRON_SESSION_PREFIX + job.getId(), Instant.now());
        return new MessageContext(job.getChatId(), user, msg);
    }
}
