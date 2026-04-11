package com.arcclaw.core.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.ToolOperation;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Component A2 — CheckListTool.
 * Allows agents to track progress for multi-step tasks.
 */
@ApplicationScoped
public class CheckListTool implements AgentTool {

    @Inject
    Event<CheckListEvent> checkListEvents;

    @Inject
    AuditService auditService;

    // Data model
    public record CheckList(List<CheckListItem> checkList) {
        public record CheckListItem(String content, Status status, String activeForm) {}
        public enum Status { pending, in_progress, completed }
    }

    @Override
    public String name() {
        return ToolCapability.MANAGE_CHECKLIST.toolName();
    }

    @Override
    public String description() {
        return ToolCapability.MANAGE_CHECKLIST.description();
    }

    @Override
    public boolean requiresSandbox() {
        return false;
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.READ_FS;
    }

    @Override
    public boolean requiresManualApproval(Map<String, Object> params) {
        return false;
    }

    public JsonObjectSchema parametersSchema() {
        // Single parameter: "checkList" — an array of objects
        return JsonObjectSchema.builder()
            .addProperty("checkList", JsonArraySchema.builder()
                .items(JsonObjectSchema.builder()
                    .addStringProperty("content", "The item content/task description.")
                    .addEnumProperty("status", List.of("pending", "in_progress", "completed"))
                    .addStringProperty("activeForm", "The reference form/id associated with this step.")
                    .required("content", "status", "activeForm")
                    .build())
                .build())
            .required("checkList")
            .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResponse execute(Map<String, Object> params) {
        String userId;
        String sessionId;
        String agentRole;

        try {
            // Step 1 — Extract and validate tenant context
            // NS2: Fail fast on missing context
            userId    = WorkspaceContext.requireUserId();
            sessionId = WorkspaceContext.requireSessionId();
            agentRole = WorkspaceContext.requireAgentRole();
        } catch (IllegalStateException e) {
            // NS2 backward compatibility: convert missing context into 403 ToolResponse
            if (e.getMessage() != null && e.getMessage().contains("unbound")) {
                return ToolResponse.failure(403, "Security violation: tenant context not bound.");
            }
            throw e;
        }
 
        Object checkListRaw = params.get("checkList");
        if (checkListRaw == null) {
            throw new IllegalArgumentException("checkList must not be null or empty.");
        }
        if (!(checkListRaw instanceof List)) {
             throw new IllegalArgumentException("checkList parameter must be an array.");
        }

        List<Map<String, Object>> rawList = (List<Map<String, Object>>) checkListRaw;
        if (rawList.isEmpty()) {
            throw new IllegalArgumentException("checkList must not be null or empty.");
        }

        List<CheckList.CheckListItem> items = new ArrayList<>();
        int inProgressCount = 0;

        for (Map<String, Object> map : rawList) {
            String content = (String) map.get("content");
            String statusStr = (String) map.get("status");
            String activeForm = (String) map.get("activeForm");

            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("Each item's content must not be blank.");
            }
            if (activeForm == null || activeForm.isBlank()) {
                throw new IllegalArgumentException("Each item's activeForm must not be blank.");
            }
            if (statusStr == null) {
                throw new IllegalArgumentException("Each item's status must not be null.");
            }

            CheckList.Status status;
            try {
                status = CheckList.Status.valueOf(statusStr.toLowerCase());
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid status: " + statusStr);
            }

            if (status == CheckList.Status.in_progress) {
                inProgressCount++;
            }

            items.add(new CheckList.CheckListItem(content, status, activeForm));
        }

        // Validation rules
        if (inProgressCount > 1) {
            throw new IllegalArgumentException(
                "At most ONE item may have status == in_progress at any time. Found: " + inProgressCount);
        }

        CheckList checkList = new CheckList(items);

        // Fire event
        checkListEvents.fire(new CheckListEvent(sessionId, checkList));

        // Audit — NS2: Fail fast on missing role
        auditService.record(sessionId, userId, agentRole, 
            SecurityEventType.TOOL_EXECUTED, "Checklist updated with " + items.size() + " items");
 
        return ToolResponse.success("Checklist updated. Continue tracking progress.");
    }
}
