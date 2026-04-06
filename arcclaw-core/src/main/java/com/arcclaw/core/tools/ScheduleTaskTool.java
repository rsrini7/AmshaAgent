package com.arcclaw.core.tools;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.CronStatus;
import com.arcclaw.core.state.InstantToTextConverter;
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
 *   <li>runOnce=true normalizes dom/month to wildcards — server always enforces single-fire</li>
 * </ul>
 *
 * FIX §1: parseTimezone() normalizes blank/null/invalid timezone to UTC.
 *         OpenRouter free-tier models frequently emit "timezone": "" — ZoneId.of("")
 *         throws DateTimeException: "Invalid ID for ZoneOffset, invalid format: "
 *         which the old code propagated directly to the user as a terminal failure.
 *
 * FIX §2: parametersSchema() now uses JsonBooleanSchema for 'runOnce'.
 *         The old schema declared it as JsonStringSchema with description
 *         'use exactly the string "true" or "false"' — this caused LLMs to emit
 *         "runOnce": "true" (quoted string) consistently. While execute() handled
 *         both forms via the switch, the schema mismatch wasted an iteration on
 *         schema parse correction. Boolean schema makes LLMs emit the JSON literal.
 */
@ApplicationScoped
public class ScheduleTaskTool implements AgentTool {

    public static final String NAME = ToolCapability.SCHEDULE_TASK.toolName();

    /**
     * Rejects vague goals with unresolved references (zero LLM cost — pure regex).
     */
    private static final Pattern NAKED_PRONOUN = Pattern.compile(
            "\\b(this|that|the code|the file|the thing|the result)\\b",
            Pattern.CASE_INSENSITIVE
    );

    @Inject AgentCronRepository cronRepository;
    @Inject AuditService auditService;

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "Schedule a future autonomous task. " +
               "The task will execute at the time pre-determined from the user's message context (e.g. 'in 5 minutes'). " +
               "Parameters: " +

               "1. 'goal' - Fully self-contained instructions. Must describe ONLY the final action. " +
                       "Do NOT include time references or re-scheduling instructions in the goal. " +
                       "NEVER use pronouns — replace 'it/this/that' with the explicit noun. " +

               "2. 'timezone' - Optional IANA timezone (e.g. 'Asia/Kolkata'). Defaults to 'UTC'. " +
                       "Leave unset or pass 'UTC' if unsure — do NOT pass an empty string. " +

               "3. 'runOnce' - JSON boolean literal true or false (NOT a string). " +
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
                .addProperty("timezone", JsonStringSchema.builder()
                        .description("IANA timezone for cron interpretation (default: UTC). " +
                                "Example: 'Asia/Kolkata', 'America/New_York'. " +
                                "Omit or use 'UTC' if unknown — do NOT pass an empty string.")
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
        // Step 1 — Extract and validate tenant context
        String userId    = WorkspaceContext.userId();
        String chatId    = WorkspaceContext.chatId();
        String sessionId = WorkspaceContext.requireSessionId();

        if (userId == null || userId.isBlank()) {
            return ToolResponse.failure(403,
                    "Security violation: tenant context not bound. Operation rejected.");
        }
        if (chatId.isBlank()) {
            chatId = userId; // REST/CLI fallback
        }

        String goal           = (String) params.get("goal");

        // FIX §1: parseTimezone() — normalize blank/null/invalid to UTC.
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

        String executionRole = (String) params.get("executionRole");
        if (executionRole == null || executionRole.isBlank()) {
            executionRole = AgentRoleEnum.RESEARCHER.toScopedName();
        }

        // Step 1 — Validate cronExpression or absolute time trigger
        String cronExpression = (String) params.get("cronExpression");
        if (cronExpression == null || cronExpression.isBlank()) {
            cronExpression = WorkspaceContext.suggestedCron();
        }

        String suggestedNextFireTime = WorkspaceContext.suggestedNextFireTime();
        if ((cronExpression == null || cronExpression.isBlank()) 
                && !(runOnce && suggestedNextFireTime != null)) {
            return ToolResponse.failure(400, "Schedule context missing. " +
                    "Example: instead of 'deploy it', write 'deploy commit abc123 " +
                    "to production via gh workflow run deploy.yml'.");
        }
        if (goal == null || goal.isBlank()) {
            return ToolResponse.failure(400, "goal is required.");
        }

        // Step 2 — Naked pronoun guard
        boolean hasNakedIt = goal.split("\\s+").length < 6
                && goal.toLowerCase().matches(".*\\bit\\b.*");
        boolean hasNakedPronoun = NAKED_PRONOUN.matcher(goal).find() || hasNakedIt;
        if (hasNakedPronoun) {
            return ToolResponse.failure(422,
                    "Goal contains unresolved references (e.g., 'it', 'the code'). " +
                    "Rewrite with explicit technical context before scheduling. " +
                    "Example: instead of 'deploy it', write 'deploy commit abc123 " +
                    "to production via gh workflow run deploy.yml'.");
        }

        // Step 3 — RunOnce normalization: strip dom/month pinning.
        // LLM sometimes generates "54 15 3 3 *" for one-shot tasks.
        // runOnce=true already guarantees single-fire — dom/month are always redundant
        // and dangerous (task silently never fires if the cron tick crosses midnight).
        if (runOnce && cronExpression != null) {
            String[] fields = cronExpression.trim().split("\\s+");
            if (fields.length == 5) {
                cronExpression = fields[0] + " " + fields[1] + " * * *";
            }
        }

        // Step 4 — Validate and compute nextFireTime via cron-utils
        Instant nextFireTime;
        if (runOnce && suggestedNextFireTime != null && !suggestedNextFireTime.isBlank()) {
            try {
                nextFireTime = Instant.parse(suggestedNextFireTime);
                Logger.getLogger(ScheduleTaskTool.class).infof("[ScheduleTaskTool] Using absolute one-shot trigger: %s", nextFireTime);
            } catch (Exception e) {
                Logger.getLogger(ScheduleTaskTool.class).warnf("[ScheduleTaskTool] Failed to parse suggestedNextFireTime '%s' — falling back to cron", suggestedNextFireTime);
                nextFireTime = (cronExpression != null && !cronExpression.isBlank()) ? computeNextFromCron(cronExpression, zone) : null;
            }
        } else {
            nextFireTime = (cronExpression != null && !cronExpression.isBlank()) ? computeNextFromCron(cronExpression, zone) : null;
        }

        if (nextFireTime == null) {
            return ToolResponse.failure(400, "Could not determine next fire time for '" + cronExpression + "'. Use 5-field UNIX format.");
        }

        // Step 5 — Build and persist entity
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

        // Step 6 — Audit (The actor role is COORDINATOR, the creator of the task).
        auditService.record(sessionId, userId,
                AgentRoleEnum.COORDINATOR.toScopedName(),
                SecurityEventType.CRONCREATED,
                "cronId=" + entity.id + " expression=" + cronExpression
                + " tz=" + entity.timezone + " runOnce=" + runOnce + " role=" + executionRole);

        return ToolResponse.success("Task scheduled successfully. " +
                "ID: " + entity.id + " | Next fire: " +
                InstantToTextConverter.format(nextFireTime) +
                "\n\nCRITICAL DIRECTIVE: The task is now safely handed off to the background cron system. " +
                "You MUST NOT execute the goal yourself right now. " +
                "Reply to the user with a plain text confirmation of the schedule and FINISH immediately.");
    }

    /**
     * Parses the 'timezone' parameter defensively.
     *
     * Normalization rules (in order):
     * 1. null or missing              → UTC
     * 2. blank string ("")            → UTC  ← FIX: OpenRouter free emits this frequently
     * 3. literal string "null"        → UTC
     * 4. valid IANA ID                → ZoneId.of(raw)
     * 5. unrecognized ID              → UTC  (warn + fallback, never propagate exception)
     *
     * The resolved ZoneId is used for nextFireTime computation and also
     * stored back via entity.timezone = zone.getId() so the DB always has
     * a canonical IANA string, never an empty value.
     */
    private ZoneId parseTimezone(Map<String, Object> params) {
        if (!params.containsKey("timezone")) return ZoneOffset.UTC;

        String raw = String.valueOf(params.get("timezone")).strip();
        if (raw.isEmpty() || "null".equalsIgnoreCase(raw)) return ZoneOffset.UTC;

        try {
            return ZoneId.of(raw);
        } catch (DateTimeException e) {
            // Unrecognized timezone — warn and fall back rather than propagating
            // a 400 error that would be incorrectly fast-pathed as a terminal answer.
            Logger.getLogger(ScheduleTaskTool.class)
                .warnf("[ScheduleTaskTool] Unrecognized timezone '%s' — defaulting to UTC", raw);
            return ZoneOffset.UTC;
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
