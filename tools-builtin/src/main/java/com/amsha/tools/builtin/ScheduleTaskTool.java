package com.amsha.tools.builtin;

import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.amsha.common.util.LogUtil;
import com.amsha.common.util.TemporalUtils;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.cron.CronTaskScheduled;
import com.amsha.core.domain.model.AgentCron;
import com.amsha.core.domain.model.AgentCronRecord;
import com.amsha.core.util.CronTimeResolver;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStatus;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolMetadata;
import com.amsha.tool.api.ToolNames;
import com.amsha.tool.api.ToolResponse;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

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
@Unremovable
@ToolMetadata(
        capabilityId = ToolNames.SCHEDULE_TASK,
        description = "Schedule future or continuous background tasks",
        defaultRoles = {AgentRoleEnum.COORDINATOR_ID, AgentRoleEnum.SYNTHESIZER_ID})
public class ScheduleTaskTool implements AgentTool {

    private static final Logger log = Logger.getLogger(ScheduleTaskTool.class);

    public static final String TOOL_NAME = ToolCapability.SCHEDULE_TASK.toolName();
    private static final String PARAM_GOAL = "goal";
    private static final String PARAM_CRON_EXPRESSION = "cronExpression";
    private static final String PARAM_TIMEZONE = "timezone";
    private static final String PARAM_RUN_ONCE = "runOnce";

    /**
     * Rejects vague goals with unresolved references (zero LLM cost - pure regex).
     */
    private static final Pattern NAKED_PRONOUN =
            Pattern.compile("\\b(this|the code|the file|the thing)\\b", Pattern.CASE_INSENSITIVE);

    private static final String DEFAULT_EXECUTION_ROLE = AgentRoleEnum.RESEARCHER.toScopedName();

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * Minimum lead time a resolved fire time must retain. A target at or before "now" is
     * stale and must never be persisted silently.
     */
    private static final Duration MIN_FIRE_LEAD_TIME = Duration.ofSeconds(5);

    @Inject
    CronStorePort cronStore;

    @Inject
    AuditService auditService;

    @Inject
    Event<CronTaskScheduled> cronScheduledEvent;

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return "Schedule a future autonomous task. "
                + "The task will execute at the time pre-determined from the user's message context (e.g. 'in 5 minutes'). "
                + "Parameters: "
                + "1. 'goal' - Fully self-contained instructions. Must describe ONLY the final action. "
                + "Do NOT include time references or re-scheduling instructions in the goal. "
                + "NEVER use pronouns - replace 'it/this/that' with the explicit noun. "
                + "2. 'cronExpression' - Required for recurring tasks (runOnce=false). "
                + "Provide a valid 5-field UNIX cron expression. "
                + "Examples: '*/1 * * * *' (every minute), '*/5 * * * *' (every 5 minutes), "
                + "'0 * * * *' (every hour), '0 9 * * *' (daily at 9 AM UTC). "
                + "Omit only for one-time tasks (runOnce=true). "
                + "3. 'timezone' - Optional IANA timezone (e.g. 'Asia/Kolkata'). Defaults to 'UTC'. "
                + "Leave unset or pass 'UTC' if unsure - do NOT pass an empty string. "
                + "4. 'runOnce' - JSON boolean literal true or false (NOT a string). "
                + "true for one-time tasks, false for recurring. "
                + "Returns the scheduled task ID immediately. Does NOT execute the goal now.";
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
                .addProperty(
                        PARAM_GOAL,
                        JsonStringSchema.builder()
                                .description("Fully self-contained action for the future agent. "
                                        + "No pronouns, no time references, no re-scheduling instructions.")
                                .build())
                .addProperty(
                        PARAM_CRON_EXPRESSION,
                        JsonStringSchema.builder()
                                .description("Required for recurring tasks (runOnce=false). "
                                        + "Provide a valid 5-field UNIX cron expression. "
                                        + "Examples: '*/1 * * * *' (every minute), '*/5 * * * *' (every 5 minutes), "
                                        + "'0 * * * *' (every hour), '0 9 * * *' (daily at 9 AM UTC). "
                                        + "Omit only for one-time tasks (runOnce=true).")
                                .build())
                .addProperty(
                        PARAM_TIMEZONE,
                        JsonStringSchema.builder()
                                .description("IANA timezone for cron interpretation (default: UTC). "
                                        + "Example: 'Asia/Kolkata', 'America/New_York'. "
                                        + "Omit or use 'UTC' if unknown - do NOT pass an empty string.")
                                .build())
                .addProperty(
                        PARAM_RUN_ONCE,
                        JsonAnyOfSchema.builder()
                                .anyOf(
                                        JsonBooleanSchema.builder().build(),
                                        JsonStringSchema.builder().build())
                                .description("true for a one-time task, false for recurring. "
                                        + "Use a JSON boolean or string 'true'/'false'.")
                                .build())
                .addProperty(
                        "executionRole",
                        JsonStringSchema.builder()
                                .description("The agent role that will EXECUTE this task when it fires. "
                                        + "Use 'researcher' for web search, GitHub, or data fetching tasks. "
                                        + "Use 'coder' for code generation or file manipulation tasks. "
                                        + "Use 'coordinator' only if the task itself needs to schedule further tasks. "
                                        + "Default: 'researcher'.")
                                .build())
                .required(PARAM_GOAL)
                .build();
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        log.infof("[ScheduleTaskTool] Executing with params: %s", params);

        ToolResponse validationError = validateExecutionContext(ctx);
        if (validationError != null) return validationError;

        String goal = (String) params.get(PARAM_GOAL);
        ZoneId requestedZone = parseTimezone(params);
        ZoneId zone = requestedZone;
        boolean runOnce = parseRunOnce(params);
        if (!runOnce) {
            runOnce = shouldTreatAsRunOnce(goal, ctx, runOnce);
        }
        String executionRole = parseExecutionRole(params);

        String cronExpression = resolveCron(params);
        cronExpression = resolveCronOrDefault(cronExpression, ctx);
        boolean cronFromJavaSide =
                (cronExpression != null && !cronExpression.equals(params.get(PARAM_CRON_EXPRESSION)));

        if (cronFromJavaSide || "UTC".equals(ctx.suggestedCronSource())) {
            zone = ZoneId.of("UTC");
        }

        String suggestedNextFireTime = ctx.suggestedNextFireTime();
        if ((cronExpression == null || cronExpression.isBlank())
                && !(runOnce && suggestedNextFireTime != null && !suggestedNextFireTime.isBlank())
                && !runOnce) {
            log.warnf("[ScheduleTaskTool] Rejecting: no cron and no suggested fire time for recurring task.");
            return ToolResponse.failure(
                    400,
                    "Missing required parameter: cronExpression. "
                            + "For recurring tasks (runOnce=false), you must supply a valid 5-field UNIX cron expression. "
                            + "Examples: '*/1 * * * *' for every minute, '0 * * * *' for every hour, "
                            + "'0 9 * * *' for daily at 9 AM UTC. "
                            + "For one-time tasks (runOnce=true), the system will compute the fire time automatically.");
        }

        if (goal == null || goal.isBlank()) {
            return ToolResponse.failure(400, "goal is required.");
        }

        ToolResponse pronounError = checkNakedPronouns(goal);
        if (pronounError != null) return pronounError;

        if (runOnce && cronExpression != null) {
            String[] fields = WHITESPACE_PATTERN.split(cronExpression.trim(), -1);
            if (fields.length == 5) {
                cronExpression = fields[0] + " " + fields[1] + " * * *";
            }
        }

        // A one-shot target is re-resolved from the user's own wording against CURRENT time.
        // The LLM only ever saw a time snapshot captured before its inference, so an absolute
        // target it produced can already be in the past once this tool runs; preferring the
        // fresh resolution keeps date/time arithmetic in Java (antigravity principle).
        Instant nextFireTime = null;
        if (runOnce) {
            ZonedDateTime fresh =
                    CronTimeResolver.resolveTargetTimeFromNow(ctx.rawUserGoal(), requestedZone, MIN_FIRE_LEAD_TIME);
            nextFireTime = fresh == null ? null : fresh.toInstant();
        }
        if (nextFireTime == null) {
            nextFireTime = resolveNextFireTime(runOnce, suggestedNextFireTime, cronExpression, zone);
        }
        if (nextFireTime == null) {
            log.warnf(
                    "[ScheduleTaskTool] Rejecting: could not determine next fire time (cron='%s', suggested='%s')",
                    cronExpression, suggestedNextFireTime);
            return ToolResponse.failure(
                    400, "Could not determine next fire time. Supply a valid cronExpression or use runOnce=true.");
        }
        // Backstop: never persist a fire time that has already elapsed. Without this a
        // run-once cron silently rolls over to its next occurrence instead of failing.
        if (!nextFireTime.isAfter(Instant.now())) {
            log.warnf(
                    "[ScheduleTaskTool] Rejecting: resolved fire time %s is already in the past "
                            + "(cron='%s', suggested='%s')",
                    nextFireTime, cronExpression, suggestedNextFireTime);
            return ToolResponse.failure(
                    400,
                    "The requested fire time has already elapsed, so the task cannot be scheduled. "
                            + "Re-issue the request with a delay measured from the current time.");
        }

        if ((cronExpression == null || cronExpression.isBlank()) && nextFireTime != null) {
            ZonedDateTime targetZdt = nextFireTime.atZone(zone);
            cronExpression = String.format(
                    Locale.ROOT,
                    "%d %d %d %d *",
                    targetZdt.getMinute(),
                    targetZdt.getHour(),
                    targetZdt.getDayOfMonth(),
                    targetZdt.getMonthValue());
        }

        String userId = ctx.userId();
        String chatId = ctx.chatId() != null ? ctx.chatId() : userId;
        String sessionId = ctx.sessionId();
        AgentCron entity = createAndPersistCron(
                ctx, sessionId, userId, chatId, cronExpression, zone, runOnce, executionRole, goal, nextFireTime);

        auditAndSchedule(sessionId, userId, cronExpression, entity, runOnce, executionRole);

        return ToolResponse.success("Task scheduled successfully. " + "ID: "
                + entity.getId() + " | Next fire: " + TemporalUtils.formatIsoLex(nextFireTime)
                + "\n\nCRITICAL DIRECTIVE: The task is now safely handed off to the background cron system. "
                + "You MUST NOT execute the goal yourself right now. "
                + "Reply to the user with a plain text confirmation of the schedule and FINISH immediately.");
    }

    private ToolResponse validateExecutionContext(ToolExecutionContext ctx) {
        String userId = ctx.userId();
        String sessionId = ctx.sessionId();
        if (userId == null || userId.isBlank() || sessionId == null) {
            return ToolResponse.failure(403, "Security violation: tenant context not bound.");
        }
        return null;
    }

    private boolean shouldTreatAsRunOnce(String goal, ToolExecutionContext ctx, boolean runOnce) {
        if (runOnce) return true;
        String combined = "";
        if (goal != null) combined += " " + goal.toLowerCase(Locale.ROOT);
        if (ctx.rawUserGoal() != null) combined += " " + ctx.rawUserGoal().toLowerCase(Locale.ROOT);

        boolean hasIntervalKeyword =
                combined.contains("every") || combined.contains("recurring") || combined.contains("each");
        boolean hasOffsetKeyword = combined.contains("after")
                || combined.contains("in ")
                || combined.contains("in a ")
                || combined.contains("once");

        return hasOffsetKeyword && !hasIntervalKeyword;
    }

    private String resolveCronOrDefault(String cronExpression, ToolExecutionContext ctx) {
        if (cronExpression == null || cronExpression.isBlank()) {
            return ctx.suggestedCron();
        }
        return cronExpression;
    }

    private ToolResponse checkNakedPronouns(String goal) {
        boolean hasNakedPronoun = NAKED_PRONOUN.matcher(goal).find();
        if (hasNakedPronoun) {
            log.warnf("[ScheduleTaskTool] Rejecting goal due to naked pronouns: %s", goal);
            return ToolResponse.failure(
                    422,
                    "Goal contains unresolved references (e.g., 'it', 'the code'). "
                            + "Rewrite with explicit technical context before scheduling. "
                            + "Example: instead of 'deploy it', write 'deploy commit abc123 "
                            + "to production via gh workflow run deploy.yml'.");
        }
        return null;
    }

    private boolean parseRunOnce(Map<String, Object> params) {
        return switch (params.get(PARAM_RUN_ONCE)) {
            case Boolean b -> b;
            case String s -> Boolean.parseBoolean(s);
            case null, default -> false;
        };
    }

    private String parseExecutionRole(Map<String, Object> params) {
        String executionRole = (String) params.get("executionRole");
        if (executionRole != null && executionRole.isBlank()) {
            executionRole = null;
        }
        if (executionRole == null) {
            return DEFAULT_EXECUTION_ROLE;
        }
        try {
            return AgentRoleEnum.fromString(executionRole).toScopedName();
        } catch (IllegalArgumentException _) {
            return DEFAULT_EXECUTION_ROLE;
        }
    }

    private String resolveCron(Map<String, Object> params) {
        String cronExpression = (String) params.get(PARAM_CRON_EXPRESSION);
        if (cronExpression == null || cronExpression.isBlank()) {
            cronExpression = (String) params.get("cron");
        }
        return cronExpression;
    }

    private Instant resolveNextFireTime(
            boolean runOnce, String suggestedNextFireTime, String cronExpression, ZoneId zone) {
        if (runOnce && suggestedNextFireTime != null && !suggestedNextFireTime.isBlank()) {
            try {
                return Instant.parse(suggestedNextFireTime);
            } catch (Exception e) {
                return (cronExpression != null && !cronExpression.isBlank())
                        ? computeNextFromCron(cronExpression, zone)
                        : null;
            }
        } else if (runOnce && (cronExpression == null || cronExpression.isBlank())) {
            return Instant.now().plusSeconds(10);
        } else {
            return (cronExpression != null && !cronExpression.isBlank())
                    ? computeNextFromCron(cronExpression, zone)
                    : null;
        }
    }

    private AgentCron createAndPersistCron(
            ToolExecutionContext ctx,
            String sessionId,
            String userId,
            String chatId,
            String cronExpression,
            ZoneId zone,
            boolean runOnce,
            String executionRole,
            String goal,
            Instant nextFireTime) {
        String safeCron = (cronExpression != null && !cronExpression.isBlank())
                ? cronExpression
                : (nextFireTime != null
                        ? String.format(
                                Locale.ROOT,
                                "%d %d %d %d *",
                                nextFireTime.atZone(zone).getMinute(),
                                nextFireTime.atZone(zone).getHour(),
                                nextFireTime.atZone(zone).getDayOfMonth(),
                                nextFireTime.atZone(zone).getMonthValue())
                        : "* * * * *");
        var dto = new AgentCronDto(
                UUID.randomUUID().toString(),
                sessionId,
                userId,
                chatId,
                ctx.channelType(),
                ctx.channelPlatformId(),
                safeCron,
                zone.getId(),
                runOnce,
                AgentSentinels.CRON_PREFIX + goal,
                nextFireTime,
                null,
                CronStatus.ACTIVE.name(),
                executionRole,
                null,
                null,
                Instant.now(),
                null);
        cronStore.save(dto);
        return AgentCronRecord.fromDto(dto);
    }

    private void auditAndSchedule(
            String sessionId,
            String userId,
            String cronExpression,
            AgentCron entity,
            boolean runOnce,
            String executionRole) {
        auditService.record(
                sessionId,
                userId,
                AgentRoleEnum.COORDINATOR.toScopedName(),
                SecurityEventType.CRONCREATED,
                "cronId=" + entity.getId() + " expression=" + cronExpression + " tz=" + entity.getTimezone()
                        + " runOnce=" + runOnce + " role=" + executionRole);

        cronScheduledEvent.fire(new CronTaskScheduled(entity.getId()));
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
        if (!params.containsKey(PARAM_TIMEZONE)) return ZoneId.of("UTC");

        String raw = String.valueOf(params.get(PARAM_TIMEZONE)).strip();
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
            CronParser parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
            Cron cron = parser.parse(cronExpression);
            cron.validate();
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            ZonedDateTime now = ZonedDateTime.now(zone);
            return executionTime
                    .nextExecution(now)
                    .orElseThrow(() -> new IllegalArgumentException("Cron expression will never fire"))
                    .toInstant();
        } catch (RuntimeException e) {
            Logger.getLogger(ScheduleTaskTool.class)
                    .warnf("[ScheduleTaskTool] Invalid cron '%s': %s", cronExpression, LogUtil.rootMessage(e));
            return null;
        }
    }
}
