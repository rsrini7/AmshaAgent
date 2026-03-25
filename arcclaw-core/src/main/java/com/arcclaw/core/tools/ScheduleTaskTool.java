package com.arcclaw.core.tools;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
 */
@ApplicationScoped
public class ScheduleTaskTool implements AgentTool {

    public static final String NAME = ToolCapability.SCHEDULE_TASK.toolName();

    /**
     * Rejects vague goals with unresolved references (zero LLM cost — pure regex).
     * The LLM self-corrects via the existing TOOL REJECTION HANDLING prompt rule.
     */
    private static final Pattern NAKED_PRONOUN = Pattern.compile(
            "\\b(this|that|the code|the file|the thing|the result)\\b",
            Pattern.CASE_INSENSITIVE
    );

    @Inject
    AgentCronRepository cronRepository;

    @Inject
    AuditService auditService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Schedule a future autonomous task using a cron expression. " +
                "The task will execute at the specified time(s) without user interaction. " +
                "Parameters: " +
                "1. 'cronExpression' - Standard UNIX 5-field cron (minute hour dom month dow). " +
                        "For runOnce=true, ALWAYS use wildcards for dom and month: 'minute hour * * *'. " +
                        "Example: '53 5 * * *' = next occurrence of 05:53. " +
                        "Example: '0 9 * * 1-5' = every weekday at 9 AM (recurring). " +
                "2. 'goal' - Fully self-contained instructions. Must describe ONLY the final action. " +
                        "Do NOT include time references or re-scheduling instructions in the goal. " +
                        "NEVER use pronouns — replace 'it/this/that' with the explicit noun. " +
                "3. 'timezone' - Optional IANA timezone (e.g. 'Asia/Kolkata'). Defaults to 'UTC'. " +
                "4. 'runOnce' - true for one-time tasks, false for recurring. " +
                "CRON ARITHMETIC: To compute minute for 'in N minutes': " +
                "target_minute = (current_minute + N) mod 60. " +
                "Increment hour by 1 if target_minute < current_minute (wrap). " +
                "Show your arithmetic before generating the cronExpression. " +
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
                                "For runOnce=true always use wildcards: 'minute hour * * *'. " +
                                "Example: '53 5 * * *' for a one-shot at 05:53.")
                        .build())
                .addProperty("goal", JsonStringSchema.builder()
                        .description("Fully self-contained action for the future agent. " +
                                "No pronouns, no time references, no re-scheduling instructions.")
                        .build())
                .addProperty("timezone", JsonStringSchema.builder()
                        .description("IANA timezone for cron interpretation (default: UTC). " +
                                "Example: 'Asia/Kolkata', 'America/New_York'.")
                        .build())
                .addProperty("runOnce", JsonBooleanSchema.builder()
                        .description("true for one-time tasks, false for recurring (default: false).")
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
        String timezone       = params.containsKey("timezone")
                ? (String) params.get("timezone") : "UTC";
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

        // Only flag "it" if the entire goal is under 6 words — likely no context
        boolean hasNakedIt = goal.split("\\s+").length < 6
                && goal.toLowerCase().matches(".*\\bit\\b.*");
        boolean hasNakedPronoun = NAKED_PRONOUN.matcher(goal).find() || hasNakedIt;

        // Step 2 — Naked pronoun guard
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
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(timezone));
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
        entity.channelType    = WorkspaceContext.channelType();
        entity.cronExpression = cronExpression;
        entity.timezone       = timezone;
        entity.runOnce        = runOnce;
        entity.goal           = "CRON: " + goal;
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
}