package com.arcclaw.core.tools;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.cron.CronTaskScheduled;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.CronStatus;
import com.arcclaw.core.util.TemporalUtils;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.jboss.logging.Logger;

/**
 * Agent tool for scheduling autonomous future tasks via cron expressions.
 * <p>
 * Security boundaries:
 * <ul>
 *   <li>Requires {@code SCHEDULE_AUTONOMOUS} operation (blocked under READONLY/BACKGROUND)</li>
 *   <li>Tenant context must be bound (fail-closed on missing userId/chatId)</li>
 *   <li>Naked pronoun guard rejects vague goals with unresolved references</li>
 *   <li>Goal is prefixed with "CRON: " to enable fork bomb structural exclusion</li>
 *   <li>runOnce=true normalizes dom/month to wildcards - server always enforces single-fire</li>
 * </ul>
 *
 * FIX §1: parseTimezone() normalizes blank/null/invalid timezone to UTC.
 *         OpenRouter free-tier models frequently emit "timezone": "" - ZoneId.of("")
 *         throws DateTimeException: "Invalid ID for ZoneOffset, invalid format: "
 *         which the old code propagated directly to the user as a terminal failure.
 *
 * FIX §2: parametersSchema() now uses JsonBooleanSchema for 'runOnce'.
 *         The old schema declared it as JsonStringSchema with description
 *         'use exactly the string "true" or "false"' - this caused LLMs to emit
 *         "runOnce": "true" (quoted string) consistently. While execute() handled
 *         both forms via the switch, the schema mismatch wasted an iteration on
 *         schema parse correction. Boolean schema makes LLMs emit the JSON literal.
 */
@ApplicationScoped
@ToolMetadata(
    capabilityId = "schedule_task",
    description = "Schedule future or continuous background tasks",
    defaultRoles = {"coordinator", "synthesizer"}
)
public class ScheduleTaskTool implements AgentTool {

    private static final Logger log = Logger.getLogger(ScheduleTaskTool.class);

    public static final String NAME = ToolCapability.SCHEDULE_TASK.toolName();

    /**
     * Rejects vague goals with unresolved references (zero LLM cost - pure regex).
     */
    private static final Pattern NAKED_PRONOUN = Pattern.compile(
            "\\b(this|the code|the file|the thing)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    @Inject AgentCronRepository cronRepository;
    @Inject AuditService auditService;
    @Inject Event<CronTaskScheduled> cronScheduledEvent;

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "Schedule a future autonomous task. " +
               "The task will execute at the time pre-determined from the user's message context (e.g. 'in 5 minutes'). " +
               "Parameters: " +

               "1. 'goal' - Fully self-contained instructions. Must describe ONLY the final action. " +
                       "Do NOT include time references or re-scheduling instructions in the goal. " +
                       "NEVER use pronouns - replace 'it/this/that' with the explicit noun. " +

               "2. 'cronExpression' - Required for recurring tasks (runOnce=false). " +
                       "Provide a valid 5-field UNIX cron expression. " +
                       "Examples: '*/1 * * * *' (every minute), '*/5 * * * *' (every 5 minutes), " +
                       "'0 * * * *' (every hour), '0 9 * * *' (daily at 9 AM UTC). " +
                       "Omit only for one-time tasks (runOnce=true). " +

               "3. 'timezone' - Optional IANA timezone (e.g. 'Asia/Kolkata'). Defaults to 'UTC'. " +
                       "Leave unset or pass 'UTC' if unsure - do NOT pass an empty string. " +

               "4. 'runOnce' - JSON boolean literal true or false (NOT a string). " +
                       "true for one-time tasks, false for recurring. " +

               "Returns the scheduled task ID immediately. Does NOT execute the goal now.";
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.SCHEDULE_AUTONOMOUS;
    }

    @Override
    public boolean requiresSandbox() {
        return false;
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
                .addProperty("goal", JsonStringSchema.builder()
                        .description("Fully self-contained action for the future agent. " +
                                "No pronouns, no time references, no re-scheduling instructions.")
                        .build())
                .addProperty("cronExpression", JsonStringSchema.builder()
                        .description("Required for recurring tasks (runOnce=false). " +
                                "Provide a valid 5-field UNIX cron expression. " +
                                "Examples: '*/1 * * * *' (every minute), '*/5 * * * *' (every 5 minutes), " +
                                "'0 * * * *' (every hour), '0 9 * * *' (daily at 9 AM UTC). " +
                                "Omit only for one-time tasks (runOnce=true).")
                        .build())
                .addProperty("timezone", JsonStringSchema.builder()
                        .description("IANA timezone for cron interpretation (default: UTC). " +
                                "Example: 'Asia/Kolkata', 'America/New_York'. " +
                                "Omit or use 'UTC' if unknown - do NOT pass an empty string.")
                        .build())
                .addProperty("runOnce", JsonBooleanSchema.builder()
                        .description("true for a one-time task, false for recurring. " +
                                "Must be JSON boolean literal true or false, NOT a string.")
                        .build())
                .addProperty("executionRole", JsonStringSchema.builder()
                        .description("The agent role that will EXECUTE this task when it fires. " +
                                "Use 'researcher' for web search, GitHub, or data fetching tasks. " +
                                "Use 'coder' for code generation or file manipulation tasks. " +
                                "Use 'coordinator' only if the task itself needs to schedule further tasks. " +
                                "Default: 'researcher'.")
                        .build())
                .required("goal")
                .build();
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        log.infof("[ScheduleTaskTool] Executing with params: %s", params);
        String userId;
        String chatId;
        String sessionId;

        try {
            userId    = WorkspaceContext.requireUserId();
            chatId    = WorkspaceContext.requireChatIdOrUserId();
            sessionId = WorkspaceContext.requireSessionId();
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("unbound")) {
                return ToolResponse.failure(403, "Security violation: tenant context not bound.");
            }
            throw e;
        }

        String goal = (String) params.get("goal");
        ZoneId zone = parseTimezone(params);
        boolean runOnce = parseRunOnce(params);
        String executionRole = parseExecutionRole(params);

        String cronExpression = resolveCron(params);
        String suggestedNextFireTime = WorkspaceContext.suggestedNextFireTime();
        
        boolean cronFromJavaSide = false;
        if (cronExpression == null || cronExpression.isBlank()) {
            cronExpression = WorkspaceContext.suggestedCron();
            if (cronExpression != null && !cronExpression.isBlank()) {
                cronFromJavaSide = true;
            }
        }

        if (cronFromJavaSide || "UTC".equals(WorkspaceContext.suggestedCronSource())) {
            zone = ZoneId.of("UTC");
        }

        if ((cronExpression == null || cronExpression.isBlank()) 
                && !(runOnce && suggestedNextFireTime != null && !suggestedNextFireTime.isBlank())
                && !runOnce) {
            log.warnf("[ScheduleTaskTool] Rejecting: no cron and no suggested fire time for recurring task.");
            return ToolResponse.failure(400, "Missing required parameter: cronExpression. " +
                    "For recurring tasks (runOnce=false), you must supply a valid 5-field UNIX cron expression. " +
                    "Examples: '*/1 * * * *' for every minute, '0 * * * *' for every hour, " +
                    "'0 9 * * *' for daily at 9 AM UTC. " +
                    "For one-time tasks (runOnce=true), the system will compute the fire time automatically.");
        }

        if (goal == null || goal.isBlank()) {
            return ToolResponse.failure(400, "goal is required.");
        }

        boolean hasNakedPronoun = NAKED_PRONOUN.matcher(goal).find();
        if (hasNakedPronoun) {
            log.warnf("[ScheduleTaskTool] Rejecting goal due to naked pronouns: %s", goal);
            return ToolResponse.failure(422,
                    "Goal contains unresolved references (e.g., 'it', 'the code'). " +
                    "Rewrite with explicit technical context before scheduling. " +
                    "Example: instead of 'deploy it', write 'deploy commit abc123 " +
                    "to production via gh workflow run deploy.yml'.");
        }

        if (runOnce && cronExpression != null) {
            String[] fields = WHITESPACE_PATTERN.split(cronExpression.trim());
            if (fields.length == 5) {
                cronExpression = fields[0] + " " + fields[1] + " * * *";
            }
        }

        Instant nextFireTime = resolveNextFireTime(runOnce, suggestedNextFireTime, cronExpression, zone);
        if (nextFireTime == null) {
            log.warnf("[ScheduleTaskTool] Rejecting: could not determine next fire time (cron='%s', suggested='%s')", 
                cronExpression, suggestedNextFireTime);
            return ToolResponse.failure(400, "Could not determine next fire time. Supply a valid cronExpression or use runOnce=true.");
        }

        AgentCronEntity entity = createAndPersistCron(sessionId, userId, chatId, cronExpression, zone, runOnce, executionRole, goal, nextFireTime);

        auditAndSchedule(sessionId, userId, cronExpression, entity, runOnce, executionRole);

        return ToolResponse.success("Task scheduled successfully. " +
                "ID: " + entity.id + " | Next fire: " +
                TemporalUtils.formatIsoLex(nextFireTime) +
                "\n\nCRITICAL DIRECTIVE: The task is now safely handed off to the background cron system. " +
                "You MUST NOT execute the goal yourself right now. " +
                "Reply to the user with a plain text confirmation of the schedule and FINISH immediately.");
    }

    private boolean parseRunOnce(Map<String, Object> params) {
        Object runOnceRaw = params.get("runOnce");
        return switch (runOnceRaw) {
            case Boolean b -> b;
            case String s  -> Boolean.parseBoolean(s);
            case null      -> false;
            default        -> false;
        };
    }

    private String parseExecutionRole(Map<String, Object> params) {
        String executionRole = (String) params.get("executionRole");
        if (executionRole != null && executionRole.isBlank()) {
            executionRole = null;
        }
        return executionRole;
    }

    private String resolveCron(Map<String, Object> params) {
        String cronExpression = (String) params.get("cronExpression");
        if (cronExpression == null || cronExpression.isBlank()) {
            cronExpression = (String) params.get("cron");
        }
        return cronExpression;
    }

    private Instant resolveNextFireTime(boolean runOnce, String suggestedNextFireTime, String cronExpression, ZoneId zone) {
        if (runOnce && suggestedNextFireTime != null && !suggestedNextFireTime.isBlank()) {
            try {
                return Instant.parse(suggestedNextFireTime);
            } catch (Exception e) {
                return (cronExpression != null && !cronExpression.isBlank()) ? computeNextFromCron(cronExpression, zone) : null;
            }
        } else if (runOnce && (cronExpression == null || cronExpression.isBlank())) {
            return Instant.now().plusSeconds(10); 
        } else {
            return (cronExpression != null && !cronExpression.isBlank()) ? computeNextFromCron(cronExpression, zone) : null;
        }
    }

    private AgentCronEntity createAndPersistCron(String sessionId, String userId, String chatId, String cronExpression, ZoneId zone, boolean runOnce, String executionRole, String goal, Instant nextFireTime) {
        AgentCronEntity entity = new AgentCronEntity();
        entity.id             = UUID.randomUUID().toString();
        entity.sessionId      = sessionId;
        entity.userId         = userId;
        entity.chatId         = chatId;
        entity.channelType    = WorkspaceContext.requireChannelType();
        entity.cronExpression = cronExpression;
        entity.timezone       = zone.getId();
        entity.runOnce        = runOnce;
        entity.executionRole  = executionRole;
        entity.goal           = AgentSentinels.CRON_PREFIX + goal;
        entity.nextFireTime   = nextFireTime;
        entity.status         = CronStatus.ACTIVE;
        entity.createdAt      = Instant.now();

        cronRepository.persist(entity);
        return entity;
    }

    private void auditAndSchedule(String sessionId, String userId, String cronExpression, AgentCronEntity entity, boolean runOnce, String executionRole) {
        auditService.record(sessionId, userId,
                AgentRoleEnum.COORDINATOR.toScopedName(),
                SecurityEventType.CRONCREATED,
                "cronId=" + entity.id + " expression=" + cronExpression
                + " tz=" + entity.timezone + " runOnce=" + runOnce + " role=" + executionRole);

        cronScheduledEvent.fire(new CronTaskScheduled(entity.id));
    }

    /**
     * Parses the 'timezone' parameter defensively.
     *
     * Normalization rules (in order):
     * 1. null or missing              → UTC
     * 2. blank string ("")            → UTC  ← OpenRouter free emits this frequently
     * 3. literal string "null"        → UTC
     * 4. valid IANA ID                → ZoneId.of(raw)
     * 5. unrecognized ID              → UTC  (warn + fallback, never propagate exception)
     *
     * The resolved ZoneId is used for nextFireTime computation and also
     * stored back via entity.timezone = zone.getId() so the DB always has
     * a canonical IANA string, never an empty value.
     */
    private ZoneId parseTimezone(Map<String, Object> params) {
        if (!params.containsKey("timezone")) return ZoneId.of("UTC");

        String raw = String.valueOf(params.get("timezone")).strip();
        if (raw.isEmpty() || "null".equalsIgnoreCase(raw)) return ZoneId.of("UTC");

        try {
            return ZoneId.of(raw);
        } catch (DateTimeException e) {
            // Unrecognized timezone - warn and fall back rather than propagating
            // a 400 error that would be incorrectly fast-pathed as a terminal answer.
            Logger.getLogger(ScheduleTaskTool.class)
                .warnf("[ScheduleTaskTool] Unrecognized timezone '%s' - defaulting to UTC", raw);
            return ZoneId.of("UTC");
        }
    }

    private Instant computeNextFromCron(String cronExpression, ZoneId zone) {
        try {
            CronParser parser = new CronParser(
                    CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
            Cron cron = parser.parse(cronExpression);
            cron.validate();
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            ZonedDateTime now = ZonedDateTime.now(zone);
            return executionTime.nextExecution(now)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Cron expression will never fire"))
                    .toInstant();
        } catch (RuntimeException e) {
            Logger.getLogger(ScheduleTaskTool.class).warnf("[ScheduleTaskTool] Invalid cron '%s': %s", cronExpression, e.getMessage());
            return null;
        }
    }
}
