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
            "\\b(this|that|the code|the file|the thing)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern IT_PRONOUN_PATTERN = Pattern.compile(".*\\bit\\b.*", Pattern.CASE_INSENSITIVE);

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
            // Step 1 - Extract and validate tenant context
            // NS2: Fail fast on missing context
            userId    = WorkspaceContext.requireUserId();
            chatId    = WorkspaceContext.requireChatIdOrUserId(); // REST/CLI fallback 
            sessionId = WorkspaceContext.requireSessionId();
        } catch (IllegalStateException e) {
            // NS2 backward compatibility: convert missing context into 403 ToolResponse
            if (e.getMessage() != null && e.getMessage().contains("unbound")) {
                return ToolResponse.failure(403, "Security violation: tenant context not bound.");
            }
            throw e;
        }

        String goal           = (String) params.get("goal");

        //  §1: parseTimezone() - normalize blank/null/invalid to UTC.
        // ZoneId.of("") throws DateTimeException: "Invalid ID for ZoneOffset, invalid format: "
        // OpenRouter free-tier models frequently emit "timezone": "" when they don't know
        // the timezone. The old code passed the raw value directly to ZoneId.of() and the
        // resulting exception propagated as a 400 failure that was incorrectly fast-pathed
        // to a terminal answer instead of retried. Now we default gracefully to UTC.
        ZoneId zone = parseTimezone(params);

        Object runOnceRaw = params.get("runOnce");
        boolean runOnce = switch (runOnceRaw) {
            case Boolean b -> b;
            case String s  -> Boolean.parseBoolean(s);
            case null      -> false;
            default        -> false;
        };

        // NPE-SAFE: executionRole can be null. CronJobRunner.resolveRole()
        // handles null by falling back to keyword-based resolution (e.g. "write python" -> "coder").
        // Do NOT add a hardcoded default here as it overrides the runner's specialist detection.
        String executionRole = (String) params.get("executionRole");
        if (executionRole != null && executionRole.isBlank()) {
            executionRole = null;
        }

        // Step 1 - Validate cronExpression or absolute time trigger
        String cronExpression = (String) params.get("cronExpression");
        // Also accept 'cron' as an alias (the compact prompt uses cron= syntax)
        if (cronExpression == null || cronExpression.isBlank()) {
            cronExpression = (String) params.get("cron");
        }
        String suggestedNextFireTime = WorkspaceContext.suggestedNextFireTime();
        boolean cronFromJavaSide = false;
        
        // If LLM didn't provide one, try the suggested one from context
        if (cronExpression == null || cronExpression.isBlank()) {
            cronExpression = WorkspaceContext.suggestedCron();
            if (cronExpression != null && !cronExpression.isBlank()) {
                cronFromJavaSide = true;
            }
        }

        // FIX: When the cron was pre-resolved to UTC by Java-side arithmetic
        // (CommandDispatchHandler / ChatCommand), override the LLM-provided
        // timezone to UTC. Otherwise, the scheduler would interpret a UTC cron
        // expression in the user's local timezone, causing double conversion.
        // e.g. "9 AM IST" → Java resolves to UTC 03:30 → cron "30 3 * * *"
        //   Without fix: stored as cron="30 3 * * *" + TZ=Asia/Kolkata → fires 3:30 AM IST (wrong)
        //   With fix:    stored as cron="30 3 * * *" + TZ=UTC → fires 9:00 AM IST (correct)
        if (cronFromJavaSide || "UTC".equals(WorkspaceContext.suggestedCronSource())) {
            zone = ZoneId.of("UTC");
        }

        // We require AT LEAST a cronExpression OR an absolute fire time (for runOnce)
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

        // Step 2 - Naked pronoun guard
        boolean hasNakedIt = WHITESPACE_PATTERN.split(goal).length < 6
                && IT_PRONOUN_PATTERN.matcher(goal).matches();
        boolean hasNakedPronoun = NAKED_PRONOUN.matcher(goal).find() || hasNakedIt;
        if (hasNakedPronoun) {
            log.warnf("[ScheduleTaskTool] Rejecting goal due to naked pronouns: %s", goal);
            return ToolResponse.failure(422,
                    "Goal contains unresolved references (e.g., 'it', 'the code'). " +
                    "Rewrite with explicit technical context before scheduling. " +
                    "Example: instead of 'deploy it', write 'deploy commit abc123 " +
                    "to production via gh workflow run deploy.yml'.");
        }

        // Step 3 - RunOnce normalization: strip dom/month pinning.
        // LLM sometimes generates "54 15 3 3 *" for one-shot tasks.
        // runOnce=true already guarantees single-fire - dom/month are always redundant
        // and dangerous (task silently never fires if the cron tick crosses midnight).
        if (runOnce && cronExpression != null) {
            String[] fields = WHITESPACE_PATTERN.split(cronExpression.trim());
            if (fields.length == 5) {
                cronExpression = fields[0] + " " + fields[1] + " * * *";
            }
        }

        // Step 4 - Validate and compute nextFireTime via cron-utils
        Instant nextFireTime;
        if (runOnce && suggestedNextFireTime != null && !suggestedNextFireTime.isBlank()) {
            try {
                nextFireTime = Instant.parse(suggestedNextFireTime);
                Logger.getLogger(ScheduleTaskTool.class).infof("[ScheduleTaskTool] Using absolute one-shot trigger: %s", nextFireTime);
            } catch (Exception e) {
                Logger.getLogger(ScheduleTaskTool.class).warnf("[ScheduleTaskTool] Failed to parse suggestedNextFireTime '%s' - falling back to cron", suggestedNextFireTime);
                nextFireTime = (cronExpression != null && !cronExpression.isBlank()) ? computeNextFromCron(cronExpression, zone) : null;
            }
        } else if (runOnce && (cronExpression == null || cronExpression.isBlank())) {
            // §Fix: Default one-shot to "now" if no time was suggested and no cron provided.
            // We add a 10s buffer to allow for persistence ingestion and initial polling.
            // This case typically fires when an agent (e.g., in a cron-resumed session) calls
            // schedule_task without a TimeContextInjector in scope to provide a fire time hint.
            nextFireTime = Instant.now().plusSeconds(10); 
            Logger.getLogger(ScheduleTaskTool.class).infof("[ScheduleTaskTool] No time suggested for one-shot task - defaulting to 'now' (+10s): %s", nextFireTime);
        } else {
            nextFireTime = (cronExpression != null && !cronExpression.isBlank()) ? computeNextFromCron(cronExpression, zone) : null;
        }

        if (nextFireTime == null) {
            log.warnf("[ScheduleTaskTool] Rejecting: could not determine next fire time (cron='%s', suggested='%s')", 
                cronExpression, suggestedNextFireTime);
            return ToolResponse.failure(400, "Could not determine next fire time. Supply a valid cronExpression or use runOnce=true.");
        }

        // Step 5 - Build and persist entity
        AgentCronEntity entity = new AgentCronEntity();
        entity.id             = UUID.randomUUID().toString();
        entity.sessionId      = sessionId;
        entity.userId         = userId;
        entity.chatId         = chatId;
        entity.channelType    = WorkspaceContext.requireChannelType();
        entity.cronExpression = cronExpression;
        entity.timezone       = zone.getId(); // persist the resolved zone, not the raw input
        entity.runOnce        = runOnce;
        entity.executionRole  = executionRole;
        entity.goal           = AgentSentinels.CRON_PREFIX + goal;
        entity.nextFireTime   = nextFireTime;
        entity.status         = CronStatus.ACTIVE;
        entity.createdAt      = Instant.now();

        cronRepository.persist(entity);

        // Step 6 - Audit (The actor role is COORDINATOR, the creator of the task).
        auditService.record(sessionId, userId,
                AgentRoleEnum.COORDINATOR.toScopedName(),
                SecurityEventType.CRONCREATED,
                "cronId=" + entity.id + " expression=" + cronExpression
                + " tz=" + entity.timezone + " runOnce=" + runOnce + " role=" + executionRole);

        cronScheduledEvent.fire(new CronTaskScheduled(entity.id));

        return ToolResponse.success("Task scheduled successfully. " +
                "ID: " + entity.id + " | Next fire: " +
                TemporalUtils.formatIsoLex(nextFireTime) +
                "\n\nCRITICAL DIRECTIVE: The task is now safely handed off to the background cron system. " +
                "You MUST NOT execute the goal yourself right now. " +
                "Reply to the user with a plain text confirmation of the schedule and FINISH immediately.");
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
