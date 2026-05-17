package com.arcclaw.core.tools;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.task.TaskFile;
import com.arcclaw.core.task.TaskManager;
import com.arcclaw.core.task.TaskState;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Agent-callable tool for creating, querying, and managing markdown-based task files.
 * <p>
 * Tasks are plain Markdown files with YAML frontmatter in the workspace — no database required.
 */
@ApplicationScoped
@ToolMetadata(
    capabilityId = "agent_task",
    description = "Create, list, update, and transition agent task files. " +
        "Tasks are stored as Markdown files with YAML frontmatter.",
    defaultRoles = {"coordinator", "synthesizer", "researcher"}
)
public class AgentTaskTool implements AgentTool {

    @Inject
    TaskManager taskManager;

    @Inject
    AuditService auditService;

    @Override
    public String name() {
        return ToolCapability.AGENT_TASK.toolName();
    }

    @Override
    public String description() {
        return "Create, list, update, and transition agent task files. " +
               "Actions: create (name, description), list, list_active, " +
               "transition (taskId, newStatus), update (taskId, body), delete (taskId). " +
               "Status values: todo, in_progress, completed, awaiting_human_input, cancelled.";
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
            .addStringProperty("action",
                "Operation to perform: create, list, list_active, get, transition, update, delete")
            .addStringProperty("name", "Task name (required for create)")
            .addStringProperty("description", "Task description / markdown body")
            .addStringProperty("taskId", "Task file id (required for get/transition/update/delete)")
            .addStringProperty("newStatus",
                "New status for transition: todo, in_progress, completed, awaiting_human_input, cancelled")
            .required("action")
            .build();
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String action = params.getOrDefault("action", "list").toString().trim().toLowerCase();

        return switch (action) {
            case "create" -> doCreate(params);
            case "list" -> doList();
            case "list_active" -> doListActive();
            case "get" -> doGet(params);
            case "transition" -> doTransition(params);
            case "update" -> doUpdate(params);
            case "delete" -> doDelete(params);
            default -> ToolResponse.failure(400, "Unknown action: " + action);
        };
    }

    private ToolResponse doCreate(Map<String, Object> params) {
        String name = (String) params.get("name");
        if (name == null || name.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'name' is required for create.");
        }
        String description = (String) params.getOrDefault("description", "");
        TaskFile task = taskManager.createTask(name.trim(), description);
        auditService.record(null, null, null, SecurityEventType.TOOL_EXECUTED,
            "AgentTask: created task " + task.name());
        return ToolResponse.success("Created task: " + task.name() + " (id: " + task.id() + ")");
    }

    private ToolResponse doList() {
        List<TaskFile> tasks = taskManager.listTasks();
        if (tasks.isEmpty()) return ToolResponse.success("No tasks found.");
        return ToolResponse.success(tasks.stream()
            .map(t -> "• [" + t.status().name().toLowerCase() + "] " + t.name() + " — " + t.id())
            .collect(Collectors.joining("\n")));
    }

    private ToolResponse doListActive() {
        List<TaskFile> tasks = taskManager.listActiveTasks();
        if (tasks.isEmpty()) return ToolResponse.success("No active tasks.");
        return ToolResponse.success(tasks.stream()
            .map(t -> "• [" + t.status().name().toLowerCase() + "] " + t.name() + " — " + t.id())
            .collect(Collectors.joining("\n")));
    }

    private ToolResponse doGet(Map<String, Object> params) {
        String id = (String) params.get("taskId");
        if (id == null || id.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'taskId' is required for get.");
        }
        return taskManager.getTask(id.trim())
            .map(t -> ToolResponse.success(
                "Task: " + t.name() + "\nStatus: " + t.status().name().toLowerCase()
                + "\nCreated: " + t.createdAt() + "\nUpdated: " + t.updatedAt()
                + "\n\n" + t.body()))
            .orElse(ToolResponse.failure(404, "Task not found: " + id));
    }

    private ToolResponse doTransition(Map<String, Object> params) {
        String id = (String) params.get("taskId");
        String statusStr = (String) params.get("newStatus");
        if (id == null || id.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'taskId' is required for transition.");
        }
        if (statusStr == null || statusStr.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'newStatus' is required for transition.");
        }
        try {
            TaskState newState = TaskState.fromString(statusStr);
            TaskFile updated = taskManager.transitionTo(id.trim(), newState);
            return ToolResponse.success("Task '" + updated.name() + "' is now " + updated.status().name().toLowerCase());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ToolResponse.failure(400, e.getMessage());
        }
    }

    private ToolResponse doUpdate(Map<String, Object> params) {
        String id = (String) params.get("taskId");
        String body = (String) params.get("description");
        if (id == null || id.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'taskId' is required for update.");
        }
        if (body == null || body.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'description' is required for update.");
        }
        TaskFile updated = taskManager.updateBody(id.trim(), body);
        return ToolResponse.success("Updated task: " + updated.name());
    }

    private ToolResponse doDelete(Map<String, Object> params) {
        String id = (String) params.get("taskId");
        if (id == null || id.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'taskId' is required for delete.");
        }
        boolean ok = taskManager.deleteTask(id.trim());
        return ok
            ? ToolResponse.success("Deleted task: " + id)
            : ToolResponse.failure(404, "Task not found: " + id);
    }
}
