package com.arcclaw.core.tools;

import static com.arcclaw.core.message.UserMessages.NO_SCHEDULE_FOUND;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.domain.model.AgentCron;
import com.arcclaw.core.domain.model.AgentCronRecord;
import com.arcclaw.core.message.UserMessages;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.persistence.api.CronStorePort;

/**
 * Agent tool for listing and deleting scheduled cron tasks.
 * <p>
 * Uses {@code READ_FS} operation since listing is read-only
 * and deletion is tenant-isolated (user can only delete their own jobs).
 */
@ApplicationScoped
@ToolMetadata(
        capabilityId = "manage_schedule",
        description = "Manage (list/delete) existing scheduled tasks",
        defaultRoles = {"coordinator", "synthesizer"})
public class ManageScheduleTool implements AgentTool {

    @Inject
    CronStorePort cronStore;

    @Inject
    AuditService auditService;

    @Override
    public String name() {
        return ToolCapability.MANAGE_SCHEDULE.toolName();
    }

    @Override
    public String description() {
        return "List or delete scheduled autonomous tasks. " + "Parameters: "
                + "1. 'action' - 'list' to show all your scheduled tasks, "
                + "'delete' to remove a specific task. "
                + "2. 'cronId' - Required when action is 'delete'. "
                + "Obtain the cronId first by calling with action='list'. "
                + "Returns a formatted list of tasks with their IDs, cron expressions, and next fire times.";
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.READ_FS;
    }

    @Override
    public boolean requiresSandbox() {
        return false;
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
                .addProperty(
                        "action",
                        JsonStringSchema.builder()
                                .description("Action to perform: 'list' or 'delete'")
                                .build())
                .addProperty(
                        "cronId",
                        JsonStringSchema.builder()
                                .description("ID of the cron job to delete (required for delete action)")
                                .build())
                .required("action")
                .build();
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String userId;
        String sessionId;

        try {
            // Step 1 - Extract and validate tenant context - NS2: Fail fast
            userId = ctx.userId();
            sessionId = ctx.sessionId();
        } catch (IllegalStateException e) {
            // NS2 backward compatibility: convert missing context into 403 ToolResponse
            if (e.getMessage() != null && e.getMessage().contains("unbound")) {
                return ToolResponse.failure(403, "Security violation: tenant context not bound.");
            }
            throw e;
        }

        String action = (String) params.get("action");
        if (action == null || action.isBlank()) {
            return ToolResponse.failure(
                    400,
                    "action is required. Use 'list' to see all cron jobs, or 'delete' with an ID. "
                            + "Example: {\"action\": \"delete\", \"cronId\": \"<uuid>\"}. "
                            + "Or use /crons in Telegram to list, /cron delete <id> to delete.");
        }

        return switch (action.trim().toLowerCase(Locale.ROOT)) {
            case "list" -> {
                List<AgentCron> jobs = cronStore.findByUserId(userId).stream()
                        .map(AgentCronRecord::fromDto)
                        .collect(Collectors.toList());
                if (jobs.isEmpty()) {
                    yield ToolResponse.success(NO_SCHEDULE_FOUND);
                }
                String listing =
                        jobs.stream().map(AgentCron::toDisplayString).collect(Collectors.joining("\n\n---\n\n"));
                yield ToolResponse.success(listing);
            }
            case "delete" -> {
                String cronId = (String) params.get("cronId");
                if (cronId == null || cronId.isBlank()) {
                    yield ToolResponse.failure(400, "cronId is required for delete action.");
                }

                int deleted = cronStore.deleteByIdAndUserId(cronId, userId); // ← capture result

                if (deleted == 0) {
                    yield ToolResponse.failure(404, UserMessages.cronNotFound(cronId));
                }

                auditService.record(
                        sessionId,
                        userId, // ← only audit on actual deletion
                        AgentRoleEnum.COORDINATOR.toScopedName(),
                        SecurityEventType.CRONDELETED,
                        "cronId=" + cronId);

                yield ToolResponse.success(UserMessages.cronDeleted(cronId));
            }

            default -> ToolResponse.failure(400, "Unknown action: " + action + ". Use 'list' or 'delete'.");
        };
    }
}
