package com.amsha.tools.builtin;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.tools.CheckList;
import com.amsha.core.tools.CheckListEvent;
import com.amsha.core.tools.CheckListItem;
import com.amsha.core.tools.CheckListStatus;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolMetadata;
import com.amsha.tool.api.ToolResponse;

import io.quarkus.arc.Unremovable;

/**
 * Component A2 - CheckListTool.
 * Allows agents to track progress for multi-step tasks.
 */
@ApplicationScoped
@Unremovable
@ToolMetadata(
        capabilityId = "manage_checklist",
        description = "Manage task checklist progress",
        defaultRoles = {"coder", "debugger"})
public class CheckListTool implements AgentTool {

    private static final String PARAM_CHECKLIST = "checkList";
    private static final String PARAM_ACTIVE_FORM = "activeForm";
    private static final String PARAM_CONTENT = "content";
    private static final String PARAM_STATUS = "status";

    @Inject
    Event<CheckListEvent> checkListEvents;

    @Inject
    AuditService auditService;

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

    @Override
    public JsonObjectSchema parametersSchema() {
        // Single parameter: "checkList" - an array of objects
        return JsonObjectSchema.builder()
                .addProperty(
                        PARAM_CHECKLIST,
                        JsonArraySchema.builder()
                                .items(JsonObjectSchema.builder()
                                        .addStringProperty(PARAM_CONTENT, "The item content/task description.")
                                        .addEnumProperty(PARAM_STATUS, List.of("pending", "in_progress", "completed"))
                                        .addStringProperty(
                                                PARAM_ACTIVE_FORM, "The reference form/id associated with this step.")
                                        .required(PARAM_CONTENT, PARAM_STATUS, PARAM_ACTIVE_FORM)
                                        .build())
                                .build())
                .required(PARAM_CHECKLIST)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String userId = ctx.userId();
        String sessionId = ctx.sessionId();
        String agentRole = ctx.agentRole();

        if (userId == null || userId.isBlank()) {
            return ToolResponse.failure(403, "Forbidden: userId is required for checklist management.");
        }

        Object checkListRaw = params.get(PARAM_CHECKLIST);
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

        List<CheckListItem> items = new ArrayList<>();
        int inProgressCount = 0;

        for (Map<String, Object> map : rawList) {
            String content = (String) map.get(PARAM_CONTENT);
            String statusStr = (String) map.get(PARAM_STATUS);
            String activeForm = (String) map.get(PARAM_ACTIVE_FORM);

            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("Each item's content must not be blank.");
            }
            if (activeForm == null || activeForm.isBlank()) {
                throw new IllegalArgumentException("Each item's activeForm must not be blank.");
            }
            if (statusStr == null) {
                throw new IllegalArgumentException("Each item's status must not be null.");
            }

            CheckListStatus status;
            try {
                status = CheckListStatus.valueOf(statusStr.toLowerCase(Locale.ROOT));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid status: " + statusStr);
            }

            if (status == CheckListStatus.in_progress) {
                inProgressCount++;
            }

            items.add(new CheckListItem(content, status, activeForm));
        }

        // Validation rules
        if (inProgressCount > 1) {
            throw new IllegalArgumentException(
                    "At most ONE item may have status == in_progress at any time. Found: " + inProgressCount);
        }

        CheckList checkList = new CheckList(items);

        // Fire event
        checkListEvents.fire(new CheckListEvent(sessionId, checkList));

        // Audit - NS2: Fail fast on missing role
        auditService.record(
                sessionId,
                userId,
                agentRole,
                SecurityEventType.TOOL_EXECUTED,
                "Checklist updated with " + items.size() + " items");

        return ToolResponse.success("Checklist updated. Continue tracking progress.");
    }
}
