package com.arcclaw.core.tools;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.state.AgentCronRepository;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent tool for listing and deleting scheduled cron tasks.
 * <p>
 * Uses {@code READ_FS} operation since listing is read-only
 * and deletion is tenant-isolated (user can only delete their own jobs).
 */
@ApplicationScoped
public class ManageScheduleTool implements AgentTool {

    @Inject
    AgentCronRepository cronRepository;

    @Inject
    AuditService auditService;

    @Override
    public String name() {
        return ToolCapability.MANAGE_SCHEDULE.toolName();
    }

    @Override
    public String description() {
        return "List or delete scheduled autonomous tasks. " +
                "Parameters: " +
                "1. 'action' - 'list' to show all your scheduled tasks, " +
                    "'delete' to remove a specific task. " +
                "2. 'cronId' - Required when action is 'delete'. " +
                    "Obtain the cronId first by calling with action='list'. " +
                "Returns a formatted list of tasks with their IDs, cron expressions, and next fire times.";
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
                .addProperty("action", JsonStringSchema.builder()
                        .description("Action to perform: 'list' or 'delete'")
                        .build())
                .addProperty("cronId", JsonStringSchema.builder()
                        .description("ID of the cron job to delete (required for delete action)")
                        .build())
                .required("action")
                .build();
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        // Tenant context validation — fail-closed
        String userId = WorkspaceScopeHolder.USER_ID.isBound()
                ? WorkspaceScopeHolder.USER_ID.get() : null;
        if (userId == null) {
            return ToolResponse.failure(403,
                    "Security violation: tenant context not bound. Operation rejected.");
        }

        String action = (String) params.get("action");
        if (action == null || action.isBlank()) {
            return ToolResponse.failure(400, "action is required (list or delete).");
        }

        return switch (action.trim().toLowerCase()) {
            case "list" -> {
                List<AgentCronEntity> jobs = cronRepository.findByUserId(userId);
                if (jobs.isEmpty()) {
                    yield ToolResponse.success("No scheduled tasks found.");
                }
                String listing = jobs.stream()
                        .map(AgentCronEntity::toDisplayString)
                        .collect(Collectors.joining("\n---\n"));
                yield ToolResponse.success(listing);
            }
            case "delete" -> {
                String cronId = (String) params.get("cronId");
                if (cronId == null || cronId.isBlank()) {
                    yield ToolResponse.failure(400, "cronId is required for delete action.");
                }
                cronRepository.deleteByIdAndUserId(cronId, userId);

                String sessionId = WorkspaceScopeHolder.getIfActive();
                auditService.record(sessionId, userId,
                        AgentRoleEnum.COORDINATOR.toScopedName(),
                        SecurityEventType.CRONDELETED,
                        "cronId=" + cronId);

                yield ToolResponse.success("Cron job " + cronId + " deleted.");
            }
            default -> ToolResponse.failure(400,
                    "Unknown action: " + action + ". Use 'list' or 'delete'.");
        };
    }
}
