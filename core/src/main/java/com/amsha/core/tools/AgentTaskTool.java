package com.amsha.core.tools;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.agent.role.ToolMetadata;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.ToolOperation;
import com.amsha.core.task.TaskFile;
import com.amsha.core.task.TaskManager;
import com.amsha.core.task.TaskState;

/**
 * Agent-callable tool for creating, querying, and managing markdown-based task files.
 * <p>
 * Tasks are plain Markdown files with YAML frontmatter in the workspace — no database required.
 */
@ApplicationScoped
@ToolMetadata(
        capabilityId = "agent_task",
        description = "Create, list, update, and transition agent task files. "
                + "Tasks are stored as Markdown files with YAML frontmatter.",
        defaultRoles = {"coordinator", "synthesizer", "researcher"})
public class AgentTaskTool implements AgentTool {

    @Inject
    TaskManager taskManager;

    private static final String PARAM_ACTION = "action";
    private static final String PARAM_NAME = "name";
    private static final String PARAM_DESCRIPTION = "description";
    private static final String PARAM_TASK_ID = "taskId";
    private static final String PARAM_NEW_STATUS = "newStatus";

    @Inject
    AuditService auditService;

    @Override
    public String name() {
        return ToolCapability.AGENT_TASK.toolName();
    }

    @Override
    public String description() {
        return "Create, list, update, and transition agent task files. "
                + "Actions: create (name, description), list, list_active, "
                + "transition (taskId, newStatus), update (taskId, body), delete (taskId). "
                + "Status values: todo, in_progress, completed, awaiting_human_input, cancelled.";
    }

    @Override
    public boolean requiresSandbox() {
        return false;
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.WRITE_FS;
    }

    @Override
    public IdempotencyMode idempotencyMode() {
        return IdempotencyMode.NON_IDEMPOTENT;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty(
                        PARAM_ACTION,
                        "Operation to perform: create, list, list_active, get, transition, update, delete")
                .addStringProperty(PARAM_NAME, "Task name (required for create)")
                .addStringProperty(PARAM_DESCRIPTION, "Task description / markdown body")
                .addStringProperty(PARAM_TASK_ID, "Task file id (required for get/transition/update/delete)")
                .addStringProperty(
                        PARAM_NEW_STATUS,
                        "New status for transition: todo, in_progress, completed, awaiting_human_input, cancelled")
                .required(PARAM_ACTION)
                .build();
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String action =
                params.getOrDefault(PARAM_ACTION, "list").toString().trim().toLowerCase(Locale.ROOT);

        return switch (action) {
            case "create" -> doCreate(ctx, params);
            case "list" -> doList();
            case "list_active" -> doListActive();
            case "get" -> doGet(params);
            case "transition" -> doTransition(ctx, params);
            case "update" -> doUpdate(ctx, params);
            case "delete" -> doDelete(ctx, params);
            default -> ToolResponse.failure(400, "Unknown action: " + action);
        };
    }

    private ToolResponse doCreate(ToolExecutionContext ctx, Map<String, Object> params) {
        String name = (String) params.get(PARAM_NAME);
        if (name == null || name.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'name' is required for create.");
        }
        String description = (String) params.getOrDefault(PARAM_DESCRIPTION, "");
        TaskFile task = taskManager.createTask(name.trim(), description);
        auditService.record(
                ctx.sessionId(),
                ctx.userId(),
                ctx.agentRole(),
                SecurityEventType.TOOL_EXECUTED,
                "AgentTask: created task=" + task.name() + " id=" + task.id());
        return ToolResponse.success("Created task: " + task.name() + " (id: " + task.id() + ")");
    }

    private ToolResponse doList() {
        List<TaskFile> tasks = taskManager.listTasks();
        if (tasks.isEmpty()) return ToolResponse.success("No tasks found.");
        return ToolResponse.success(tasks.stream()
                .map(t -> "• [" + t.status().name().toLowerCase(Locale.ROOT) + "] " + t.name() + " — " + t.id())
                .collect(Collectors.joining("\n")));
    }

    private ToolResponse doListActive() {
        List<TaskFile> tasks = taskManager.listActiveTasks();
        if (tasks.isEmpty()) return ToolResponse.success("No active tasks.");
        return ToolResponse.success(tasks.stream()
                .map(t -> "• [" + t.status().name().toLowerCase(Locale.ROOT) + "] " + t.name() + " — " + t.id())
                .collect(Collectors.joining("\n")));
    }

    private ToolResponse doGet(Map<String, Object> params) {
        String id = (String) params.get(PARAM_TASK_ID);
        if (id == null || id.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'taskId' is required for get.");
        }
        return taskManager
                .getTask(id.trim())
                .map(t -> ToolResponse.success(
                        "Task: " + t.name() + "\nStatus: " + t.status().name().toLowerCase(Locale.ROOT)
                                + "\nCreated: " + t.createdAt() + "\nUpdated: " + t.updatedAt()
                                + "\n\n" + t.body()))
                .orElse(ToolResponse.failure(404, "Task not found: " + id));
    }

    private ToolResponse doTransition(ToolExecutionContext ctx, Map<String, Object> params) {
        String id = (String) params.get(PARAM_TASK_ID);
        String statusStr = (String) params.get(PARAM_NEW_STATUS);
        if (id == null || id.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'taskId' is required for transition.");
        }
        if (statusStr == null || statusStr.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'newStatus' is required for transition.");
        }
        try {
            TaskState newState = TaskState.fromString(statusStr);
            TaskFile updated = taskManager.transitionTo(id.trim(), newState);
            auditService.record(
                    ctx.sessionId(),
                    ctx.userId(),
                    ctx.agentRole(),
                    SecurityEventType.TOOL_EXECUTED,
                    "AgentTask: transition task=" + id + " newStatus="
                            + updated.status().name());
            return ToolResponse.success("Task '" + updated.name() + "' is now "
                    + updated.status().name().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ToolResponse.failure(400, e.getMessage());
        }
    }

    private ToolResponse doUpdate(ToolExecutionContext ctx, Map<String, Object> params) {
        String id = (String) params.get(PARAM_TASK_ID);
        String body = (String) params.get(PARAM_DESCRIPTION);
        if (id == null || id.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'taskId' is required for update.");
        }
        if (body == null || body.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'description' is required for update.");
        }
        TaskFile updated = taskManager.updateBody(id.trim(), body);
        auditService.record(
                ctx.sessionId(),
                ctx.userId(),
                ctx.agentRole(),
                SecurityEventType.TOOL_EXECUTED,
                "AgentTask: update task=" + id);
        return ToolResponse.success("Updated task: " + updated.name());
    }

    private ToolResponse doDelete(ToolExecutionContext ctx, Map<String, Object> params) {
        String id = (String) params.get(PARAM_TASK_ID);
        if (id == null || id.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'taskId' is required for delete.");
        }
        boolean ok = taskManager.deleteTask(id.trim());
        if (ok) {
            auditService.record(
                    ctx.sessionId(),
                    ctx.userId(),
                    ctx.agentRole(),
                    SecurityEventType.TOOL_EXECUTED,
                    "AgentTask: deleted task=" + id);
        }
        return ok ? ToolResponse.success("Deleted task: " + id) : ToolResponse.failure(404, "Task not found: " + id);
    }
}
