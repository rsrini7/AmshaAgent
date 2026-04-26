package com.arcclaw.app.scheduler;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Callable;

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
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Orchestrates the execution of a single cron job, managing the workspace context,
 * secret seeding, and hand-off to AgentLoop.
 */
@ApplicationScoped
public class CronJobRunner {

    private static final Logger log = Logger.getLogger(CronJobRunner.class);

    @Inject
    AgentLoop agentLoop;

    @Inject
    LangChainReasoningEngine engine;

    @Inject
    CronResultHandler resultHandler;

    @Inject
    AgentCronRepository cronRepository;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @ConfigProperty(name = "arcclaw.cron.max-iterations", defaultValue = "3")
    int cronMaxIterations;

    private static final String PREFIX_OUTPUT_MSG = "output the following message:";
    private static final String PREFIX_OUTPUT     = "output ";
    private static final String PREFIX_SAY        = "say ";
    private static final String PREFIX_TELL       = "tell ";
    private static final String PREFIX_SEND_MSG   = "send message:";
    private static final String PREFIX_SEND_TEXT  = "send text:";

    public void execute(AgentCronEntity job) {
        String executionId = UUID.randomUUID().toString();
        
        cronRepository.setDeliveryId(job.id, executionId);

        String cronSessionId = AgentSentinels.CRON_SESSION_PREFIX + job.id + "-" + executionId;

        // §Issue 4: Align heartbeat autonomy with execution context to ensure consistent HITL
        heartbeatTracker.recordHeartbeat(cronSessionId, AutonomyLevel.SUPERVISED);
        
        try {
            String stripped = job.goal != null ? job.goal.replaceFirst("^CRON\\s+", "").trim() : "";
            
            // §Bug2: Short-circuit pure announcements to direct send, bypassing agent loop
            if (isPureAnnouncement(stripped)) {
                String announcement = stripAnnouncementPrefix(stripped);
                log.infof("CRON-SHORT-CIRCUIT cronId=%s announcement='%s'", job.id, announcement);
                AgentResult result = AgentResult.finished(announcement, Collections.emptyList());
                resultHandler.handleSuccess(job, cronSessionId, executionId, result);
                return;
            }

            // §Bug1: Short-circuit time checks to avoid code-gen failures in researcher role
            if (isTimeCheck(stripped)) {
                log.infof("CRON-SHORT-CIRCUIT cronId=%s time-check", job.id);
                String time = ZonedDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.RFC_1123_DATE_TIME);
                AgentResult result = AgentResult.finished("The current time is " + time, Collections.emptyList());
                resultHandler.handleSuccess(job, cronSessionId, executionId, result);
                return;
            }

            // §New Issue 1: Unwrap goals stored as tool-call strings
            String finalGoal = unwrapToolCallGoal(stripped);
            if (!finalGoal.equals(stripped)) {
                log.infof("CRON-UNWRAP cronId=%s: extracted goal '%s'", job.id, finalGoal);
            }

            String systemNote = "CRON MODE";

            ChannelType type = ChannelType.fromString(job.channelType).orElse(ChannelType.TELEGRAM);
            String role = resolveRole(finalGoal, job.executionRole);
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
                cronSessionId, job.userId, job.chatId, AutonomyLevel.SUPERVISED, role, channel, op
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
        if (normalized.startsWith(PREFIX_OUTPUT) || normalized.startsWith(PREFIX_SAY)
                || normalized.startsWith(PREFIX_TELL) 
                || normalized.startsWith(PREFIX_SEND_MSG) || normalized.startsWith(PREFIX_SEND_TEXT)) {
            return true;
        }

        // Check for lack of tool-like verbs (heuristic for simple statements)
        boolean hasActionVerb = normalized.contains("fetch") || normalized.contains("search") 
                || normalized.contains("check") || normalized.contains("monitor") 
                || normalized.contains("run") || normalized.contains("execute")
                || normalized.contains("investigate") || normalized.contains("analyze")
                || normalized.contains("view") || normalized.contains("list")
                || normalized.contains("delete") || normalized.contains("update")
                || normalized.contains("create") || normalized.contains("google")
                || normalized.contains("memory") || normalized.contains("browse")
                || normalized.contains(ToolCapability.GITHUB.toolName())
                || normalized.contains(ToolCapability.BROWSER_NAVIGATE.toolName())
                || normalized.contains(ToolCapability.WEB_SEARCH_SERPER.toolName());

        return !hasActionVerb && goal.length() < 200;
        }

        private boolean isTimeCheck(String goal) {
            if (goal == null || goal.isBlank()) return false;
            String normalized = goal.toLowerCase().trim();
            return normalized.contains("what is the current time") || 
                   normalized.contains("what time is it") ||
                   normalized.contains("tell me the time") ||
                   normalized.equals("check: what is the current time?");
        }

        private String stripAnnouncementPrefix(String goal) {
        if (goal == null) return null;
        String lower = goal.toLowerCase();
        if (lower.startsWith(PREFIX_OUTPUT_MSG)) return goal.substring(PREFIX_OUTPUT_MSG.length()).trim();
        if (lower.startsWith(PREFIX_OUTPUT))      return goal.substring(PREFIX_OUTPUT.length()).trim();
        if (lower.startsWith(PREFIX_SAY))         return goal.substring(PREFIX_SAY.length()).trim();
        if (lower.startsWith(PREFIX_TELL))        return goal.substring(PREFIX_TELL.length()).trim();
        if (lower.startsWith(PREFIX_SEND_MSG))    return goal.substring(PREFIX_SEND_MSG.length()).trim();
        if (lower.startsWith(PREFIX_SEND_TEXT))   return goal.substring(PREFIX_SEND_TEXT.length()).trim();
        return goal;
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
                // fallback to original
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
        } else if (normalized.contains("coder task") || normalized.contains("write code") || normalized.contains("fix bug") || normalized.contains("refactor")) {
            return AgentRoleEnum.CODER.toScopedName();
        }
        
        // Default to researcher for cron tasks as most involve retrieval/monitoring
        return AgentRoleEnum.RESEARCHER.toScopedName();
    }

    private MessageContext createCronContext(AgentCronEntity job, String sessionId, String executionId, String goal, ChannelType channelType) {
        ChannelUser user = new ChannelUser(job.userId, job.userId, "Cron User", channelType);
        ChannelMessage msg = new ChannelMessage.RestApiMessage(user, goal, AgentSentinels.CRON_SESSION_PREFIX + job.id, Instant.now());
        return new MessageContext(job.chatId, user, msg);
    }
}
