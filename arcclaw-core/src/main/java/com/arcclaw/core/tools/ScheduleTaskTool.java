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
        return "Schedule a future autonomous task using a cron expression. " +
               "The task will execute at the specified time(s) without user interaction. " +
               "Parameters: " +

               "1. 'cronExpression' - Standard UNIX 5-field cron (minute hour dom month dow). " +
                       "For runOnce=true, ALWAYS use ONLY minute and hour — wildcards for dom/month/dow: " +
                       "\"minute hour * * *\". " +
                       "Example one-time: \"say hi in 1 min\" at 12:15 UTC → " +
                       "cronExpression=\"16 12 * * *\", runOnce=true. " +
                       "Example recurring: \"0 9 * * 1-5\" = every weekday at 9 AM. " +
                       "NEVER pin dom or month for one-time tasks — the server strips them anyway. " +

               "2. 'goal' - Fully self-contained instructions. Must describe ONLY the final action. " +
                       "Do NOT include time references or re-scheduling instructions in the goal. " +
                       "NEVER use pronouns — replace 'it/this/that' with the explicit noun. " +

               "3. 'timezone' - Optional IANA timezone (e.g. 'Asia/Kolkata'). Defaults to 'UTC'. " +
                       "Leave unset or pass 'UTC' if unsure — do NOT pass an empty string. " +

               "4. 'runOnce' - JSON boolean literal true or false (NOT a string). " +
                       "true for one-time tasks, false for recurring. " +

               "CRON ARITHMETIC: " +
               "Your system prompt contains the CURRENT SYSTEM TIME labelled as " +
               "\"CURRENT SYSTEM TIME (UTC)\" with explicit UTC hour and minute values. " +
               "You MUST read those values before computing any relative cron expression. " +
               "NEVER guess or assume the current time. " +
               "To compute minute for 'in N minutes': " +
               "target_minute = (current_minute + N) mod 60. " +
               "Increment hour by 1 if target_minute < current_minute (minute wrapped past 59). " +
               "Show your arithmetic step-by-step before generating the cronExpression. " +
               "English words (one=1, two=2, five=5, ten=10) equal their digits. " +
               "NEVER use '* * * * *' — this fires every minute forever. " +
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
                .addProperty("cronExpression", JsonStringSchema.builder()
                        .description("Standard 5-field UNIX cron (minute hour dom month dow). " +
                                "For runOnce=true ALWAYS use wildcards for dom/month/dow: " +
                                "\"minute hour * * *\". " +
                                "Example: \"16 12 * * *\" for one-shot at 12:16 UTC.")
                        .build())
                .addProperty("goal", JsonStringSchema.builder()
                        .description("Fully self-contained action for the future agent. " +
                                "No pronouns, no time references, no re-scheduling instructions.")
                        .build())
                .addProperty("timezone", JsonStringSchema.builder()
                        .description("IANA timezone for cron interpretation (default: UTC). " +
                                "Example: 'Asia/Kolkata', 'America/New_York'. " +
                                "Omit or use 'UTC' if unknown — do NOT pass an empty string.")
                        .build())
                // FIX §2: JsonBooleanSchema instead of JsonStringSchema.
                // The old schema described runOnce as a string ("true"/"false") which caused
                // LLMs to consistently emit the quoted form "runOnce": "true".
                // JsonBooleanSchema makes the JSON Schema type=boolean, so compliant LLMs
                // emit the JSON literal: "runOnce": true  (no quotes).
                // execute() still handles both Boolean and String via the switch for safety.
                .addProperty("runOnce", JsonBooleanSchema.builder()
                        .description("true for a one-time task, false for recurring. " +
                                "Must be JSON boolean literal true or false, NOT a string.")
                        .build())
                .required("cronExpression", "goal")
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

        String cronExpression = (String) params.get("cronExpression");
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

        if (cronExpression == null || cronExpression.isBlank()) {
            return ToolResponse.failure(400, "cronExpression is required.");
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
        if (runOnce) {
            String[] fields = cronExpression.trim().split("\\s+");
            if (fields.length == 5) {
                cronExpression = fields[0] + " " + fields[1] + " * * *";
            }
        }

        // Step 4 — Validate and compute nextFireTime via cron-utils
        Instant nextFireTime;
        try {
            CronParser parser = new CronParser(
                    CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
            Cron cron = parser.parse(cronExpression);
            cron.validate();
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            ZonedDateTime now = ZonedDateTime.now(zone);
            nextFireTime = executionTime.nextExecution(now)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Cron expression will never fire"))
                    .toInstant();
        } catch (RuntimeException e) {
            return ToolResponse.failure(400,
                    "Invalid cron expression '" + cronExpression + "': " + e.getMessage() +
                    ". Use 5-field UNIX format: minute hour dom month dow");
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
        entity.goal           = AgentSentinels.CRON_PREFIX + goal;
        entity.nextFireTime   = nextFireTime;
        entity.status         = CronStatus.ACTIVE;
        entity.createdAt      = Instant.now();

        cronRepository.persist(entity);

        // Step 6 — Audit
        auditService.record(sessionId, userId,
                AgentRoleEnum.COORDINATOR.toScopedName(),
                SecurityEventType.CRONCREATED,
                "cronId=" + entity.id + " expression=" + cronExpression
                + " tz=" + entity.timezone + " runOnce=" + runOnce);

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
            org.jboss.logging.Logger.getLogger(ScheduleTaskTool.class)
                .warnf("[ScheduleTaskTool] Unrecognized timezone '%s' — defaulting to UTC", raw);
            return ZoneOffset.UTC;
        }
    }
}