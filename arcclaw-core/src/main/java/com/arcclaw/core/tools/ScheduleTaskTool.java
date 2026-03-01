package com.arcclaw.core.tools;

import com.arcclaw.core.agent.AgentRoleEnum;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.InstantToTextConverter;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Agent tool for scheduling autonomous future tasks via cron expressions.
 * <p>
 * Security boundaries:
 * <ul>
 *   <li>Requires {@code SCHEDULE_AUTONOMOUS} operation (blocked under READONLY/BACKGROUND)</li>
 *   <li>Tenant context must be bound (fail-closed on missing userId/chatId)</li>
 *   <li>Naked pronoun guard rejects vague goals with unresolved references</li>
 *   <li>Goal is prefixed with "CRON: " to enable fork bomb structural exclusion</li>
 * </ul>
 */
@ApplicationScoped
public class ScheduleTaskTool implements AgentTool {

    /**
     * Rejects vague goals with unresolved references (zero LLM cost — pure regex).
     * The LLM self-corrects via the existing TOOL REJECTION HANDLING prompt rule.
     */
    private static final Pattern NAKED_PRONOUN = Pattern.compile(
            "\\b(it|this|that|the code|the file|the thing|the result)\\b",
            Pattern.CASE_INSENSITIVE
    );

    @Inject
    AgentCronRepository cronRepository;

    @Inject
    AuditService auditService;

    @Override
    public String name() {
        return "scheduletask";
    }

    @Override
    public String description() {
        return "Schedule a future autonomous task using a cron expression. " +
               "The task will execute at the specified time(s) without user interaction.";
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
                        .description("Standard 5-field UNIX cron expression (minute hour dom month dow)")
                        .build())
                .addProperty("goal", JsonStringSchema.builder()
                        .description("Fully self-contained action description for the future agent")
                        .build())
                .addProperty("timezone", JsonStringSchema.builder()
                        .description("IANA timezone (default: UTC)")
                        .build())
                .addProperty("runOnce", JsonBooleanSchema.builder()
                        .description("If true, the task fires once then completes (default: false)")
                        .build())
                .required("cronExpression", "goal")
                .build();
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        // Step 1 — Extract and validate tenant context
        String userId = WorkspaceScopeHolder.USER_ID.isBound()
                ? WorkspaceScopeHolder.USER_ID.get() : null;
        String chatId = WorkspaceScopeHolder.CHAT_ID.isBound()
                ? WorkspaceScopeHolder.CHAT_ID.get() : null;
        String sessionId = WorkspaceScopeHolder.getIfActive();

        if (userId == null || chatId == null) {
            return ToolResponse.failure(403,
                    "Security violation: tenant context not bound. Operation rejected.");
        }

        String cronExpression = (String) params.get("cronExpression");
        String goal = (String) params.get("goal");
        String timezone = params.containsKey("timezone")
                ? (String) params.get("timezone") : "UTC";
        boolean runOnce = params.containsKey("runOnce")
                && Boolean.parseBoolean(String.valueOf(params.get("runOnce")));

        if (cronExpression == null || cronExpression.isBlank()) {
            return ToolResponse.failure(400, "cronExpression is required.");
        }
        if (goal == null || goal.isBlank()) {
            return ToolResponse.failure(400, "goal is required.");
        }

        // Step 2 — Naked pronoun guard (Section 4.7)
        if (NAKED_PRONOUN.matcher(goal).find()) {
            return ToolResponse.failure(422,
                    "Goal contains unresolved references (e.g., 'it', 'the code'). " +
                    "Rewrite with explicit technical context before scheduling. " +
                    "Example: instead of 'deploy it', write 'deploy commit abc123 " +
                    "to production via gh workflow run deploy.yml'.");
        }

        // Step 3 — Validate and compute nextFireTime via cron-utils
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
                    "Invalid cron expression: " + e.getMessage() +
                    ". Use 5-field UNIX format: minute hour dom month dow");
        }

        // Step 4 — Build and persist entity
        AgentCronEntity entity = new AgentCronEntity();
        entity.id = UUID.randomUUID().toString();
        entity.sessionId = sessionId;
        entity.userId = userId;
        entity.chatId = chatId;
        entity.cronExpression = cronExpression;
        entity.timezone = timezone;
        entity.runOnce = runOnce;
        entity.goal = "CRON: " + goal;  // Prefix to prevent fork bomb
        entity.nextFireTime = nextFireTime;
        entity.status = "ACTIVE";
        entity.createdAt = Instant.now();

        cronRepository.persist(entity);

        // Step 5 — Audit
        auditService.record(sessionId, userId,
                AgentRoleEnum.COORDINATOR.toScopedName(),
                SecurityEventType.CRONCREATED,
                "cronId=" + entity.id + " expression=" + cronExpression
                + " tz=" + entity.timezone + " runOnce=" + runOnce);

        return ToolResponse.success("Task scheduled successfully. " +
                "ID: " + entity.id + " | Next fire: " +
                InstantToTextConverter.format(nextFireTime));
    }
}
